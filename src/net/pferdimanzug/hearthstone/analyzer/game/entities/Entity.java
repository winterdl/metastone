package net.pferdimanzug.hearthstone.analyzer.game.entities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import net.pferdimanzug.hearthstone.analyzer.game.GameTag;
import net.pferdimanzug.hearthstone.analyzer.game.actions.GameAction;
import net.pferdimanzug.hearthstone.analyzer.game.cards.Card;
import net.pferdimanzug.hearthstone.analyzer.game.entities.minions.Race;
import net.pferdimanzug.hearthstone.analyzer.game.spells.Spell;
import net.pferdimanzug.hearthstone.analyzer.game.spells.trigger.SpellTrigger;
import net.pferdimanzug.hearthstone.analyzer.game.targeting.EntityReference;

public abstract class Entity {

	private String name;
	private final HashMap<GameTag, Object> tags = new HashMap<GameTag, Object>();
	// index of the owning player, i.e.
	// 0 -> owned by player 1
	// 1 -> owned by player 2
	// implemented it that way because of the same reason
	// the class TargetKey was created - lazy references are better for cloning
	private int ownerIndex;
	private final Card sourceCard;
	private final List<SpellTrigger> spellTriggers = new ArrayList<>();
	private Race race = Race.NONE;
	private int id;

	public Entity(Card sourceCard) {
		this.setName(sourceCard != null ? sourceCard.getName() : null);
		this.sourceCard = sourceCard;
	}

	public void addSpellTrigger(SpellTrigger spellTrigger) {
		spellTrigger.setHost(this);
		getSpellTriggers().add(spellTrigger);
	}

	public boolean canAttackThisTurn() {
		if (hasTag(GameTag.SUMMONING_SICKNESS) && !hasTag(GameTag.CHARGE)) {
			return false;
		}
		return getAttack() > 0 && getTagValue(GameTag.NUMBER_OF_ATTACKS) > 0;
	}

	private boolean displayGameTag(GameTag tag) {
		return tag == GameTag.CHARGE || tag == GameTag.ENRAGED || tag == GameTag.FROZEN || tag == GameTag.DIVINE_SHIELD
				|| tag == GameTag.WINDFURY || tag == GameTag.SPELL_POWER || tag == GameTag.STEALTHED || tag == GameTag.TAUNT;
	}

	public int getAttack() {
		return getTagValue(GameTag.BASE_ATTACK) + getTagValue(GameTag.ATTACK_BONUS);
	}

	public GameAction getBattlecry() {
		return (GameAction) getTag(GameTag.BATTLECRY);
	}

	public GameAction getDeathrattle() {
		return (GameAction) getTag(GameTag.DEATHRATTLE);
	}

	public Spell getEnrageSpell() {
		return (Spell) getTag(GameTag.ENRAGE_SPELL);
	}

	public abstract EntityType getEntityType();

	public int getHp() {
		return getTagValue(GameTag.HP);
	}

	public int getMaxHp() {
		return getTagValue(GameTag.MAX_HP) + getTagValue(GameTag.HP_BONUS);
	}

	public String getName() {
		return name;
	}

	public int getOwner() {
		return ownerIndex;
	}

	public Race getRace() {
		return race;
	}

	public Card getSourceCard() {
		return sourceCard;
	}

	public List<SpellTrigger> getSpellTriggers() {
		return spellTriggers;
	}

	public Object getTag(GameTag tag) {
		return tags.get(tag);
	}

	public int getTagValue(GameTag tag) {
		return tags.containsKey(tag) ? (int) tags.get(tag) : 0;
	}

	public boolean hasTag(GameTag tag) {
		return tags.get(tag) != null;
	}

	public boolean isDead() {
		return getHp() < 1;
	}

	public boolean isWounded() {
		return getHp() != getMaxHp();
	}

	public void modifyHpBonus(int value) {
		modifyTag(GameTag.HP_BONUS, value);
		modifyTag(GameTag.HP, value);
	}

	public void modifyTag(GameTag tag, int value) {
		if (!hasTag(tag)) {
			setTag(tag, 0);
		}
		setTag(tag, getTagValue(tag) + value);
	}

	public void onAttack(Entity target) {
	}

	public void removeTag(GameTag tag) {
		tags.remove(tag);
	}

	public void setBaseAttack(int value) {
		setTag(GameTag.BASE_ATTACK, value);
	}

	public void setBaseHp(int value) {
		setMaxHp(value);
		setHp(value);
	}

	public void setHp(int value) {
		setTag(GameTag.HP, value);
	}

	public void setMaxHp(int value) {
		setTag(GameTag.MAX_HP, value);
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setOwner(int ownerIndex) {
		this.ownerIndex = ownerIndex;
	}

	public void setRace(Race race) {
		this.race = race;
	}

	public void setTag(GameTag tag) {
		tags.put(tag, 1);
	}

	public void setTag(GameTag tag, int value) {
		tags.put(tag, value);
	}

	public void setTag(GameTag tag, Object value) {
		tags.put(tag, value);
	}

	@Override
	public String toString() {
		String result = "["  + getEntityType() + " '" + getName() + "'id:" + getId() + " ";
		result += getAttack() + "/" + getHp();
		String prefix = " ";
		for (GameTag tag : tags.keySet()) {
			if (displayGameTag(tag)) {
				result += prefix + tag;
				prefix = ", ";
			}
		}
		result += "]";
		return result;
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}
	
	public EntityReference getReference() {
		return EntityReference.pointTo(this);
	}
}