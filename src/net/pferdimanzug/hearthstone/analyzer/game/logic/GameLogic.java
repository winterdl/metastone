package net.pferdimanzug.hearthstone.analyzer.game.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.pferdimanzug.hearthstone.analyzer.game.GameContext;
import net.pferdimanzug.hearthstone.analyzer.game.GameTag;
import net.pferdimanzug.hearthstone.analyzer.game.Player;
import net.pferdimanzug.hearthstone.analyzer.game.actions.ActionType;
import net.pferdimanzug.hearthstone.analyzer.game.actions.GameAction;
import net.pferdimanzug.hearthstone.analyzer.game.cards.Card;
import net.pferdimanzug.hearthstone.analyzer.game.cards.CardCollection;
import net.pferdimanzug.hearthstone.analyzer.game.cards.CardType;
import net.pferdimanzug.hearthstone.analyzer.game.cards.SpellCard;
import net.pferdimanzug.hearthstone.analyzer.game.cards.concrete.neutral.TheCoin;
import net.pferdimanzug.hearthstone.analyzer.game.entities.Entity;
import net.pferdimanzug.hearthstone.analyzer.game.entities.EntityType;
import net.pferdimanzug.hearthstone.analyzer.game.entities.heroes.Hero;
import net.pferdimanzug.hearthstone.analyzer.game.entities.minions.Minion;
import net.pferdimanzug.hearthstone.analyzer.game.entities.weapons.Weapon;
import net.pferdimanzug.hearthstone.analyzer.game.events.DamageEvent;
import net.pferdimanzug.hearthstone.analyzer.game.events.KillEvent;
import net.pferdimanzug.hearthstone.analyzer.game.events.PhysicalAttackEvent;
import net.pferdimanzug.hearthstone.analyzer.game.events.SummonEvent;
import net.pferdimanzug.hearthstone.analyzer.game.events.TurnEndEvent;
import net.pferdimanzug.hearthstone.analyzer.game.events.TurnStartEvent;
import net.pferdimanzug.hearthstone.analyzer.game.heroes.powers.HeroPower;
import net.pferdimanzug.hearthstone.analyzer.game.spells.Spell;
import net.pferdimanzug.hearthstone.analyzer.game.spells.trigger.SpellTrigger;
import net.pferdimanzug.hearthstone.analyzer.game.targeting.CardReference;
import net.pferdimanzug.hearthstone.analyzer.game.targeting.EntityReference;
import net.pferdimanzug.hearthstone.analyzer.game.targeting.IdFactory;
import net.pferdimanzug.hearthstone.analyzer.game.targeting.TargetSelection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GameLogic implements IGameLogic {

	public static Logger logger = LoggerFactory.getLogger(GameLogic.class);

	public static final int MAX_MINIONS = 7;
	public static final int MAX_HAND_CARDS = 10;
	public static final int MAX_HERO_HP = 30;
	public static final int STARTER_CARDS = 3;
	public static final int MAX_MANA = 10;

	private final TargetLogic targetLogic = new TargetLogic();
	private final ActionLogic actionLogic = new ActionLogic();
	private final IdFactory idFactory = new IdFactory();
	private GameContext context;
	
	@Override
	public boolean canPlayCard(int playerId, CardReference cardReference) {
		Player player = context.getPlayer(playerId);
		Card card = context.resolveCardReference(cardReference);
		if (player.getMana() < card.getManaCost()) {
			return false;
		}
		if (card.getCardType() == CardType.HERO_POWER) {
			HeroPower power = (HeroPower) card;
			if (power.hasBeenUsed()) {
				return false;
			}
		} else if (card.getCardType() == CardType.MINION) {
			return player.getMinions().size() < MAX_MINIONS;
		} 
		
		if (card instanceof SpellCard) {
			SpellCard spellCard = (SpellCard) card;
			return spellCard.canBeCast(context, player);
		}
		return true;
	}

	@Override
	public void castSpell(int playerId, Spell spell) {
		Player player = context.getPlayer(playerId);
		spell.cast(context, player, targetLogic.resolveTargetKey(context, player, spell.getTarget()));
	}

	@Override
	public void damage(Entity target, int damage) {
		switch (target.getEntityType()) {
		case MINION:
			damageMinion((Entity) target, damage);
			break;
		case HERO:
			damageHero((Hero) target, damage);
			break;
		default:
			break;
		}
	}

	private void damageHero(Hero hero, int damage) {
		int effectiveHp = hero.getHp() + hero.getArmor();
		hero.modifyArmor(-damage);
		int newHp = Math.min(hero.getHp(), effectiveHp - damage);
		hero.setHp(newHp);
		logger.debug(hero.getName() + " receives " + damage + " damage, hp now: " + hero.getHp() + "("
				+ hero.getArmor() + ")");
	}
	
	private void damageMinion(Entity minion, int damage) {
		if (minion.hasTag(GameTag.DIVINE_SHIELD)) {
			minion.removeTag(GameTag.DIVINE_SHIELD);
			logger.debug("{}'s DIVINE SHIELD absorbs the damage", minion);
			return;
		}
		logger.debug("{} is damaged for {}", minion, damage);
		minion.setHp(minion.getHp() - damage);
		context.getEventManager().fireGameEvent(new DamageEvent(context, minion, damage));
		if (minion.hasTag(GameTag.ENRAGE_SPELL)){
			handleEnrage(minion);
		}
	}

	@Override
	public void destroy(Entity target) {
		switch (target.getEntityType()) {
		case HERO:
			logger.error("Destroying hero not implemented!");
			break;
		case MINION:
			destroyMinion(target);
			break;
		case WEAPON:
			destroyWeapon((Weapon) target);
			break;
		case ANY:
		default:
			break;
		
		}
	}
	
	private void destroyMinion(Entity minion) {
		logger.debug("{} is destroyed", minion);
		Player owner = context.getPlayer(minion.getOwner());
		owner.getMinions().remove(minion);
		for (SpellTrigger spellTrigger : minion.getSpellTriggers()) {
			context.getEventManager().removeGameEventListener(spellTrigger);
		}
		context.getEventManager().fireGameEvent(new KillEvent(context, minion));
	}
	
	private void destroyWeapon(Weapon weapon) {
		Player owner = context.getPlayer(weapon.getOwner());
		owner.getHero().setWeapon(null);
	}

	@Override
	public int determineBeginner(int... playerIds) {
		return ThreadLocalRandom.current().nextBoolean() ? playerIds[0] : playerIds[1];
	}

	@Override
	public void drawCard(int playerId) {
		Player player = context.getPlayer(playerId);
		CardCollection<Card> deck = player.getDeck();
		if (deck.isEmpty()) {
			Hero hero = player.getHero();
			int fatigue = hero.hasTag(GameTag.FATIGUE) ? hero.getTagValue(GameTag.FATIGUE) : 0;
			hero.setTag(GameTag.FATIGUE, fatigue + 1);
			damage(hero, fatigue);
			logger.debug("{}'s deck is empty, taking {} fatigue damage!", player.getName(), fatigue);
			return;
		}

		Card card = deck.removeFirst();
		receiveCard(playerId, card);
	}

	@Override
	public void endTurn(int playerId) {
		Player player = context.getPlayer(playerId);
		player.getHero().setTag(GameTag.ATTACK_BONUS, 0);
		player.getHero().removeTag(GameTag.COMBO);
		logger.debug("{} ends his turn.", player.getName());
		context.getEventManager().fireGameEvent(new TurnEndEvent(context, player.getId()));
	}

	@Override
	public void fight(Entity attacker, Entity defender) {
		logger.debug("{} attacks {}", attacker, defender);
		int attackerDamage = attacker.getAttack();
		int defenderDamage = defender.getAttack();
		damage(defender, attackerDamage);
		// heroes do not retaliate when attacked
		if (defender.getEntityType() != EntityType.HERO) {
			damage(attacker, defenderDamage);
		}
		
		attacker.modifyTag(GameTag.NUMBER_OF_ATTACKS, -1);
		context.getEventManager().fireGameEvent(new PhysicalAttackEvent(context, attacker, defender));
	}

	@Override
	public GameResult getMatchResult(Player player, Player opponent) {
		int ownHp = player.getHero().getHp();
		int opponentHp = opponent.getHero().getHp();
		if (ownHp < 1 && opponentHp < 1) {
			return GameResult.DOUBLE_LOSS;
		} else if (opponentHp < 1) {
			return GameResult.WIN;
		} else if (ownHp < 1) {
			return GameResult.DEFEAT;
		}
		return GameResult.RUNNING;
	}

	@Override
	public List<GameAction> getValidActions(int playerId) {
		Player player = context.getPlayer(playerId);
		return actionLogic.getValidActions(context, player);
	}

	@Override
	public List<Entity> getValidTargets(int playerId, GameAction action) {
		Player player = context.getPlayer(playerId);
		return targetLogic.getValidTargets(context, player, action);
	}

	private void handleEnrage(Entity entity) {
		boolean enraged = entity.getHp() < entity.getMaxHp();
		// enrage state has not changed; do nothing
		if (entity.hasTag(GameTag.ENRAGED) == enraged) {
			return;
		}
		
		if (enraged) {
			logger.debug("{} is now enraged", entity);
			entity.setTag(GameTag.ENRAGED);
		} else {
			logger.debug("{} is no longer enraged", entity);
			entity.removeTag(GameTag.ENRAGED);
		}
		
		Spell enrageSpell = (Spell) entity.getTag(GameTag.ENRAGE_SPELL);
		Player owner = context.getPlayer(entity.getOwner());
		enrageSpell.cast(context, owner, toList(entity));
	}
	
	@Override
	public void heal(Entity target, int healing) {
		switch (target.getEntityType()) {
		case MINION:
			healMinion((Entity) target, healing);
			break;
		case HERO:
			healHero((Hero) target, healing);
			break;
		default:
			break;
		}
	}

	private void healHero(Hero hero, int healing) {
		int newHp = Math.min(MAX_HERO_HP, hero.getHp() + healing);
		if (logger.isDebugEnabled()) {
			logger.debug(hero + " is healed for " + healing + ", hp now: " + hero.getHp());
		}
		
		hero.setHp(newHp);
	}

	private void healMinion(Entity minion, int healing) {
		int newHp = Math.min(minion.getMaxHp(), minion.getHp() + healing);
		if (logger.isDebugEnabled()) {
			logger.debug(minion + " is healed for " + healing + ", hp now: " + newHp + "/" + minion.getMaxHp());	
		}
		
		minion.setHp(newHp);
		if (minion.hasTag(GameTag.ENRAGE_SPELL)) {
			handleEnrage(minion);
		}
	}

	@Override
	public void init(int playerId, boolean begins) {
		Player player = context.getPlayer(playerId);
		player.getHero().setId(idFactory.generateId());
		player.getHero().setOwner(player.getId());
		player.getHero().setMaxHp(MAX_HERO_HP);
		player.getHero().setHp(MAX_HERO_HP);
		
		assignCardIds(player.getDeck());
		assignCardIds(player.getHand());
		assignCardIds(player.getGraveyard());
		
		logger.debug("Setting hero hp to {} for {}", player.getHero().getHp(), player.getName());

		player.getDeck().shuffle();
		
		for (int j = 0; j < STARTER_CARDS; j++) {
			drawCard(playerId);
		}
		// second player gets additional card + TheCoin
		if (!begins) {
			drawCard(playerId);
			TheCoin theCoin = new TheCoin();
			receiveCard(playerId, theCoin);
		}
	}
	
	private void assignCardIds(CardCollection<Card> cardCollection) {
		for (Card card : cardCollection) {
			card.setId(idFactory.generateId());
		}
	}

	@Override
	public void modifyCurrentMana(int playerId, int mana) {
		Player player = context.getPlayer(playerId);
		int newMana = Math.min(player.getMana() + mana, MAX_MANA);
		player.setMana(newMana);
	}

	@Override
	public void performGameAction(int playerId, GameAction action) {
		if (action.getTargetRequirement() == TargetSelection.SELF) {
			action.setTargetKey(EntityReference.pointTo(action.getSource()));
		}
		Player player = context.getPlayer(playerId);
		if (action.getTargetRequirement() != TargetSelection.NONE && action.getTargetKey() == null) {
			List<Entity> validTargets = getValidTargets(playerId, action);
			if (validTargets.isEmpty() && action.getActionType() == ActionType.MINION_ABILITY) {
				return;
			}
			Entity target = player.getBehaviour().provideTargetFor(player, action, validTargets);
			if (target != null) {
				if (!validTargets.contains(target)) {
					throw new IllegalArgumentException("Selected invalid target " + target.getName() + " for action "
							+ action);
				}
			}
			action.setTargetKey(EntityReference.pointTo(target));
		}

		action.execute(context, playerId);
		checkForDeadEntities();
	}
	
	private void checkForDeadEntities() {
		for (Player player : context.getPlayers()) {
			for (Minion minion : new ArrayList<Minion>(player.getMinions())) {
				if (minion.isDead()) {
					destroyMinion(minion);	
				}
			}
		}
		
	}

	@Override
	public void playCard(int playerId, CardReference cardReference) {
		Player player = context.getPlayer(playerId);
		Card card = context.resolveCardReference(cardReference);
		modifyCurrentMana(playerId, -card.getManaCost());
		logger.debug("{} plays {}", player.getName(), card);
		//logger.debug("{} is now at {} mana", player.getName(), player.getMana() + "/" + player.getMaxMana());
		player.getHand().remove(card);
		player.getGraveyard().add(card);
		player.getHero().modifyTag(GameTag.COMBO, +1);
	}
	
	@Override
	public void receiveCard(int playerId, Card card) {
		Player player = context.getPlayer(playerId);
		if (card.getId() == IdFactory.UNASSIGNED) {
			card.setId(idFactory.generateId());
		}
		CardCollection<Card> hand = player.getHand();
		if (hand.getCount() < MAX_HAND_CARDS) {
			logger.debug("{} receives card {}", player.getName(), card);
			hand.add(card);
		} else {
			CardCollection<Card> graveyard = player.getGraveyard();
			logger.debug("{} has too many cards on his hand, card destroyed: {}", player.getName(), card);
			graveyard.add(card);
		}
	}

	private void refreshAttacksPerRound(Entity entity) {
		int attacks = 1;
		if (entity.hasTag(GameTag.FROZEN)) {
			attacks = 0;
		} else if ( entity.hasTag(GameTag.WINDFURY)) {
			attacks = 2;
		}
		entity.setTag(GameTag.NUMBER_OF_ATTACKS, attacks);
	}

	// TODO: circular dependency. Very ugly, refactor!
	public void setContext(GameContext context) {
		this.context = context;
	}

	@Override
	public void startTurn(int playerId) {
		Player player = context.getPlayer(playerId);
		if (player.getMaxMana() < MAX_MANA) {
			player.setMaxMana(player.getMaxMana() + 1);
		}
		player.setMana(player.getMaxMana());
		logger.debug("{} starts his turn with {} mana", player.getName(), player.getMana() + "/" + player.getMaxMana());
		player.getHero().getHeroPower().setUsed(false);
		refreshAttacksPerRound(player.getHero());
		drawCard(playerId);
		for (Entity minion : player.getMinions()) {
			refreshAttacksPerRound(minion);
			minion.removeTag(GameTag.SUMMONING_SICKNESS);
		}
		context.getEventManager().fireGameEvent(new TurnStartEvent(context, player.getId()));
	}

	@Override
	public void summon(int playerId, Minion minion, Entity nextTo) {
		Player player = context.getPlayer(playerId);
		minion.setId(idFactory.generateId());
		logger.debug("{} summons {}", player.getName(), minion);
		refreshAttacksPerRound(minion);
		minion.setTag(GameTag.SUMMONING_SICKNESS);
		
		context.getPendingEntities().add(minion);
		if (minion.getBattlecry() != null) {
			GameAction battlecry = minion.getBattlecry();
			battlecry.setSource(minion);
			performGameAction(player.getId(), battlecry);
		}
		context.getPendingEntities().remove(minion);

		if (nextTo == null) {
			player.getMinions().add(minion);
		} else {
			// TODO: implement summoning next to
			// player.getMinions().addAfter(minion, nextTo);
		}
		minion.setOwner(player.getId());
		for (SpellTrigger spellTrigger : minion.getSpellTriggers()) {
			spellTrigger.setHost(minion);
			context.getEventManager().registerGameEventListener(spellTrigger);
		}
		if (minion.hasTag(GameTag.CHARGE)) {
			minion.setTag(GameTag.NUMBER_OF_ATTACKS, minion.hasTag(GameTag.WINDFURY) ? 2 : 1);
		} else {
			minion.setTag(GameTag.NUMBER_OF_ATTACKS, 0);
		}
		
		context.getEventManager().fireGameEvent(new SummonEvent(context, minion));
	}

	@Override
	public void useHeroPower(int playerId, HeroPower power) {
		Player player = context.getPlayer(playerId);
		modifyCurrentMana(playerId, -power.getManaCost());
		logger.debug("{} uses {}", player.getName(), power);
		power.setUsed(true);
	}

	@Override
	public void equipWeapon(int playerId, Weapon weapon) {
		Player player = context.getPlayer(playerId);
		weapon.setId(idFactory.generateId());
		logger.debug("{} equips weapon {}", player.getHero(), weapon);
		player.getHero().setWeapon(weapon);
		for (SpellTrigger spellTrigger : weapon.getSpellTriggers()) {
			context.getEventManager().registerGameEventListener(spellTrigger);
		}
	}
	
	private List<Entity> toList(Entity entity) {
		List<Entity> list = new ArrayList<Entity>(1);
		list.add(entity);
		return list;
	}

}