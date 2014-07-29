/**
 *  Copyright (C) 2002-2013   The FreeCol Team
 *
 *  This file is part of FreeCol.
 *
 *  FreeCol is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  FreeCol is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with FreeCol.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.sf.freecol.server.ai.mission;

import java.util.logging.Logger;

import javax.xml.stream.XMLStreamException;

import net.sf.freecol.common.io.FreeColXMLReader;
import net.sf.freecol.common.io.FreeColXMLWriter;
import net.sf.freecol.common.model.Ability;
import net.sf.freecol.common.model.Colony;
import net.sf.freecol.common.model.FreeColGameObject;
import net.sf.freecol.common.model.IndianSettlement;
import net.sf.freecol.common.model.Location;
import net.sf.freecol.common.model.Map.Direction;
import net.sf.freecol.common.model.PathNode;
import net.sf.freecol.common.model.Player;
import net.sf.freecol.common.model.Role;
import net.sf.freecol.common.model.Settlement;
import net.sf.freecol.common.model.Tile;
import net.sf.freecol.common.model.Unit;
import net.sf.freecol.common.model.pathfinding.CostDecider;
import net.sf.freecol.common.model.pathfinding.CostDeciders;
import net.sf.freecol.common.model.pathfinding.GoalDecider;
import net.sf.freecol.common.model.pathfinding.GoalDeciders;
import net.sf.freecol.common.util.LogBuilder;
import net.sf.freecol.server.ai.AIMain;
import net.sf.freecol.server.ai.AIMessage;
import net.sf.freecol.server.ai.AIUnit;


/**
 * Mission for sending a missionary to a native settlement.
 */
public class MissionaryMission extends Mission {

    private static final Logger logger = Logger.getLogger(MissionaryMission.class.getName());

    /** The tag for this mission. */
    private static final String tag = "AI missionary";

    /**
     * The target to aim for, used for a TransportMission.
     * Either an IndianSettlement, or a backup Colony to head for before
     * retargeting.
     */
    private Location target = null;


    /**
     * Creates a missionary mission for the given <code>AIUnit</code>.
     *
     * @param aiMain The main AI-object.
     * @param aiUnit The <code>AIUnit</code> this mission is created for.
     */
    public MissionaryMission(AIMain aiMain, AIUnit aiUnit) {
        this(aiMain, aiUnit, findTarget(aiUnit, 20, true));
    }

    /**
     * Creates a missionary mission for the given <code>AIUnit</code>.
     *
     * @param aiMain The main AI-object.
     * @param aiUnit The <code>AIUnit</code> this mission is created for.
     * @param target The target <code>Location</code> for this mission.
     */
    public MissionaryMission(AIMain aiMain, AIUnit aiUnit, Location target) {
        super(aiMain, aiUnit);

        setTarget(target);
        logger.finest(tag + " starts at " + aiUnit.getUnit().getLocation()
            + " with target " + getTarget() + ": " + this);
        uninitialized = false;
    }

    /**
     * Creates a new <code>MissionaryMission</code> and reads
     * the given element.
     *
     * @param aiMain The main AI-object.
     * @param aiUnit The <code>AIUnit</code> this mission is created for.
     * @param xr The input stream containing the XML.
     * @throws XMLStreamException if a problem was encountered
     *      during parsing.
     * @see net.sf.freecol.server.ai.AIObject#readFromXML
     */
    public MissionaryMission(AIMain aiMain, AIUnit aiUnit,
                             FreeColXMLReader xr) throws XMLStreamException {
        super(aiMain, aiUnit);

        readFromXML(xr);
        uninitialized = getAIUnit() == null;
    }


    /**
     * Extract a valid target for this mission from a path.
     *
     * @param aiUnit A <code>AIUnit</code> to perform the mission.
     * @param path A <code>PathNode</code> to extract a target from,
     *     (uses the unit location if null).
     * @return A target for this mission, or null if none found.
     */
    public static Location extractTarget(AIUnit aiUnit, PathNode path) {
        if (path == null) return null;
        final Unit unit = aiUnit.getUnit();
        final Location loc = path.getLastNode().getLocation();
        Settlement settlement = (loc == null) ? null : loc.getSettlement();
        return (settlement instanceof IndianSettlement
            && invalidIndianSettlementReason(aiUnit,
                (IndianSettlement)settlement) == null)
            ? (IndianSettlement)settlement
            : (settlement instanceof Colony
                && invalidColonyReason(aiUnit, (Colony)settlement) != null)
            ? (Colony)settlement
            : null;        
    }
    
    /**
     * Evaluate a potential cashin mission for a given unit and
     * path.
     *
     * @param aiUnit The <code>AIUnit</code> to do the mission.
     * @param path A <code>PathNode</code> to take to the target.
     * @return A score for the proposed mission.
     */
    public static int scorePath(AIUnit aiUnit, PathNode path) {
        Location loc = extractTarget(aiUnit, path);
        return (loc instanceof IndianSettlement)
            ? 1000 / (path.getTotalTurns() + 1)
            : Integer.MIN_VALUE;
    }

    /**
     * Makes a goal decider that checks for potential missions.
     *
     * @param aiUnit The <code>AIUnit</code> to find a mission with.
     * @param deferOK Keep track of the nearest of our colonies, to use
     *     as a fallback destination.
     * @return A suitable <code>GoalDecider</code>.
     */
    private static GoalDecider getGoalDecider(final AIUnit aiUnit,
                                              final boolean deferOK) {
        GoalDecider gd = new GoalDecider() {
                private PathNode bestPath = null;
                private int bestValue = Integer.MIN_VALUE;

                public PathNode getGoal() { return bestPath; }
                public boolean hasSubGoals() { return true; }
                public boolean check(Unit u, PathNode path) {
                    int value = scorePath(aiUnit, path);
                    if (bestValue < value) {
                        bestValue = value;
                        bestPath = path;
                        return true;
                    }
                    return false;
                }
            };
        return (deferOK) ? GoalDeciders.getComposedGoalDecider(false, gd,
            GoalDeciders.getOurClosestSettlementGoalDecider())
            : gd;
    }
            
    /**
     * Find a suitable mission location for this unit.
     *
     * @param aiUnit The <code>AIUnit</code> to execute this mission.
     * @param range An upper bound on the number of moves.
     * @param deferOK Enables deferring to a fallback colony.
     * @return A path to the new target, or null if none found.
     */
    private static PathNode findTargetPath(AIUnit aiUnit, int range,
                                           boolean deferOK) {
        if (invalidAIUnitReason(aiUnit) != null) return null;
        final Unit unit = aiUnit.getUnit();
        final Tile startTile = unit.getPathStartTile();
        if (startTile == null) return null;

        final Unit carrier = unit.getCarrier();
        final GoalDecider gd = getGoalDecider(aiUnit, deferOK);
        final CostDecider standardCd
            = CostDeciders.avoidSettlementsAndBlockingUnits();

        // Is there a valid target available from the starting tile?
        return unit.search(startTile, gd, standardCd, range, carrier);
    }

    /**
     * Finds a suitable mission target for the supplied unit.
     * Falls back to the best colony if a path is not found.
     *
     * @param aiUnit The <code>AIUnit</code> to test.
     * @param range An upper bound on the number of moves.
     * @param deferOK Enables deferring to a fallback colony.
     * @return A new target for this mission.
     */
    public static Location findTarget(AIUnit aiUnit, int range,
                                      boolean deferOK) {
        PathNode path = findTargetPath(aiUnit, range, deferOK);
        return (path != null) ? extractTarget(aiUnit, path)
            : upLoc(findCircleTarget(aiUnit, getGoalDecider(aiUnit, deferOK),
                                     range*3, deferOK));
    }

    /**
     * Prepare a unit for a Missionary mission.
     *
     * @param aiUnit The <code>AIUnit</code> to prepare.
     * @return A reason why the unit can not perform this mission, or null
     *     if none.
     */
    public static String prepare(AIUnit aiUnit) {
        String reason = invalidReason(aiUnit);
        if (reason == null) {
            final Unit unit = aiUnit.getUnit();
            if (!unit.hasAbility(Ability.ESTABLISH_MISSION)
                && (((FreeColGameObject)unit.getLocation())
                    .hasAbility(Ability.DRESS_MISSIONARY))) {
                aiUnit.equipForRole("model.role.missionary");
            }
            reason = (unit.hasAbility(Ability.ESTABLISH_MISSION))
                ? null
                : "unit-can-not-establish-mission";
        }
        return reason;
    }


    // Fake Transportable interface wholly inherited from Mission

    // Mission interface

    /**
     * {@inheritDoc}
     */
    public Location getTarget() {
        return target;
    }

    /**
     * {@inheritDoc}
     */
    public void setTarget(Location target) {
        if (target == null || target instanceof Settlement) {
            boolean retarget = this.target != null && this.target != target;
            this.target = target;
            if (retarget) retargetTransportable();
        }
    }

    /**
     * {@inheritDoc}
     */
    public Location findTarget() {
        return findTarget(getAIUnit(), 20, true);
    }

    /**
     * Why would this mission be invalid with the given unit?
     *
     * @param aiUnit The <code>AIUnit</code> to check.
     * @return A reason to not perform the mission, or null if none.
     */
    private static String invalidMissionReason(AIUnit aiUnit) {
        String reason = invalidAIUnitReason(aiUnit);
        if (reason != null) return reason;
        final Unit unit = aiUnit.getUnit();
        return (!unit.isPerson()) ? Mission.UNITNOTAPERSON
            : (unit.getSkillLevel() >= -1
                && !unit.hasAbility(Ability.EXPERT_MISSIONARY))
            ? "unit-is-not-subskilled-or-expertMissionary"
            : (unit.isInEurope() || unit.isAtSea()) 
            ? ((unit.getOwner().getNumberOfSettlements() <= 0)
                ? "unit-off-map-but-missing-initial-settlement"
                : null)
            : (unit.isInMission()) ? "unit-is-already-at-mission"
            : null;
    }

    /**
     * Why would a MissionaryMission be invalid with the given Colony?
     *
     * @param aiUnit The <code>AIUnit</code> to check.
     * @param colony The <code>Colony</code> to check.
     * @return A reason to not perform the mission, or null if none.
     */
    private static String invalidColonyReason(AIUnit aiUnit, Colony colony) {
        return invalidTargetReason(colony, aiUnit.getUnit().getOwner());
    }

    /**
     * Why would a MissionaryMission be invalid with the given
     * IndianSettlement?
     *
     * @param aiUnit The <code>AIUnit</code> to check.
     * @param is The <code>IndianSettlement</code> to check.
     * @return A reason to not perform the mission, or null if none.
     */
    private static String invalidIndianSettlementReason(AIUnit aiUnit,
                                                        IndianSettlement is) {
        String reason = invalidTargetReason(is);
        if (reason != null) return reason;
        final Player owner = aiUnit.getUnit().getOwner();
        return (!owner.hasContacted(is.getOwner()))
            ? "target-is-uncontacted"
            : (is.getOwner().atWarWith(owner))
            ? "target-at-war"
            : (is.hasMissionary(owner))
            ? "target-has-our-mission"
            : null;
    }

    /**
     * Why would this mission be invalid with the given AI unit?
     *
     * @param aiUnit The <code>AIUnit</code> to test.
     * @return A reason for invalidity, or null if none found.
     */
    public static String invalidReason(AIUnit aiUnit) {
        return invalidMissionReason(aiUnit);
    }

    /**
     * Why would this mission be invalid with the given AI unit and location?
     *
     * @param aiUnit The <code>AIUnit</code> to check.
     * @param loc The <code>Location</code> to check.
     * @return A reason for invalidity, or null if none found.
     */
    public static String invalidReason(AIUnit aiUnit, Location loc) {
        String reason = invalidMissionReason(aiUnit);
        return (reason != null)
            ? reason
            : (loc instanceof IndianSettlement)
            ? invalidIndianSettlementReason(aiUnit, (IndianSettlement)loc)
            : (loc instanceof Colony)
            ? invalidColonyReason(aiUnit, (Colony)loc)
            : Mission.TARGETINVALID;
    }

    /**
     * {@inheritDoc}
     */
    public String invalidReason() {
        return invalidReason(getAIUnit(), getTarget());
    }

    // Not a one-time mission, omit isOneTime().
    
    /**
     * {@inheritDoc}
     */
    public Mission doMission(LogBuilder lb) {
        lb.add(tag);
        String reason = invalidReason();
        if (isTargetReason(reason)) {
            if (!retargetMission(reason, lb)) return null;
        } else if (reason != null) {
            lbBroken(lb, reason);
            return null;
        }

        // Go to the target.
        final AIUnit aiUnit = getAIUnit();
        final Unit unit = getUnit();
        for (;;) {
            Unit.MoveType mt = travelToTarget(getTarget(),
                CostDeciders.avoidSettlementsAndBlockingUnits(), lb);
            switch (mt) {
            case MOVE_NO_MOVES: case MOVE_NO_REPAIR: case MOVE_NO_TILE:
                return this;

            case MOVE:
                // Reached an intermediate colony.  Retarget, but do not
                // accept fallback targets.
                Location completed = getTarget();
                Location newTarget = findTarget(aiUnit, 20, false);
                if (newTarget == null || newTarget == completed) {
                    setTarget(null);
                    lb.add(", reached ", completed, ", retarget failed.");
                    return null;
                }
                setTarget(newTarget);
                lb.add(tag, " reached ", completed,
                    ", retargeted " + newTarget);
                break;

            case ENTER_INDIAN_SETTLEMENT_WITH_MISSIONARY:
                Direction d = unit.getTile().getDirection(getTarget().getTile());
                assert d != null;
                IndianSettlement is = (IndianSettlement)getTarget();
                AIMessage.askEstablishMission(aiUnit, d, is.hasMissionary());
                setTarget(null);
                if (unit.isDisposed()) {
                    lbFail(lb, "died at ", is);
                } else if (is.hasMissionary(unit.getOwner())
                    && unit.isInMission()) {
                    lbDone(lb, "at ", is);
                } else {
                    lbFail(lb, "unexpected failure at ", is);
                }
                return null;

            default:
                lbMove(lb, unit, mt);
                return this;
            }
        }
    }


    // Serialization
    
    private static final String TARGET_TAG = "target";


    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeAttributes(FreeColXMLWriter xw) throws XMLStreamException {
        super.writeAttributes(xw);

        if (target != null) {
            xw.writeAttribute(TARGET_TAG, target.getId());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void readAttributes(FreeColXMLReader xr) throws XMLStreamException {
        super.readAttributes(xr);
        
        target = xr.getLocationAttribute(getGame(), TARGET_TAG, false);
    }

    /**
     * {@inheritDoc}
     */
    public String getXMLTagName() { return getXMLElementTagName(); }

    /**
     * Gets the tag name of the root element representing this object.
     *
     * @return "missionaryMission".
     */
    public static String getXMLElementTagName() {
        return "missionaryMission";
    }
}
