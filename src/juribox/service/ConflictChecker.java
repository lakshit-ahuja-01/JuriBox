package juribox.service;

import juribox.exception.CourtSchedulingException.SchedulingConflictException;
import juribox.model.*;

import java.util.*;

/**
 * Service responsible for comprehensive conflict detection across court schedules.
 * Returns simple, readable conflict messages.
 */
public class ConflictChecker {

    /**
     * Scans all schedules against registered cases, judges, and courtrooms.
     * Returns a list of clear conflict description strings.
     */
    public List<String> checkAllConflicts(Map<String, Case> caseMap,
                                         Map<String, Judge> judgeMap,
                                         Map<String, Courtroom> courtroomMap,
                                         List<Schedule> schedules,
                                         int maxDailySlots) {
        List<String> conflicts = new ArrayList<>();

        // 1. Check double bookings (same date and overlapping slots)
        for (int i = 0; i < schedules.size(); i++) {
            Schedule s1 = schedules.get(i);

            for (int j = i + 1; j < schedules.size(); j++) {
                Schedule s2 = schedules.get(j);

                if (s1.getDate().equalsIgnoreCase(s2.getDate()) && s1.overlapsWith(s2)) {
                    if (s1.getJudgeId().equalsIgnoreCase(s2.getJudgeId())) {
                        conflicts.add(String.format("Judge Double-Booking: Judge %s on %s between Case %s (%s) and Case %s (%s)",
                                s1.getJudgeId(), s1.getDate(), s1.getCaseId(), s1.getSlotRangeDescription(),
                                s2.getCaseId(), s2.getSlotRangeDescription()));
                    }
                    if (s1.getCourtroomId().equalsIgnoreCase(s2.getCourtroomId())) {
                        conflicts.add(String.format("Courtroom Double-Booking: Room %s on %s between Case %s (%s) and Case %s (%s)",
                                s1.getCourtroomId(), s1.getDate(), s1.getCaseId(), s1.getSlotRangeDescription(),
                                s2.getCaseId(), s2.getSlotRangeDescription()));
                    }
                }
            }

            // 2. Check individual schedule constraints
            Case c = caseMap.get(s1.getCaseId());
            Judge judge = judgeMap.get(s1.getJudgeId());
            Courtroom room = courtroomMap.get(s1.getCourtroomId());

            if (s1.getEndSlot() > maxDailySlots) {
                conflicts.add(String.format("Duration Conflict: Case %s runs until Slot %d (Max daily slots: %d)",
                        s1.getCaseId(), s1.getEndSlot(), maxDailySlots));
            }

            if (judge != null) {
                Set<Integer> available = judge.getAvailableSlotsForDate(s1.getDate());
                for (int slot = s1.getStartSlot(); slot <= s1.getEndSlot(); slot++) {
                    if (!available.contains(slot)) {
                        conflicts.add(String.format("Judge Unavailable: Judge %s is not working Slot %d on %s (Case %s)",
                                judge.getName(), slot, s1.getDate(), s1.getCaseId()));
                    }
                }
                if (c != null && judge.getSpecialization() != c.getRequiredSpecialization()) {
                    conflicts.add(String.format("Specialization Mismatch: Judge %s (%s) cannot hear Case %s (Requires %s)",
                            judge.getName(), judge.getSpecialization(), c.getCaseId(), c.getRequiredSpecialization()));
                }
            }

            if (room != null) {
                Set<Integer> available = room.getAvailableSlotsForDate(s1.getDate());
                for (int slot = s1.getStartSlot(); slot <= s1.getEndSlot(); slot++) {
                    if (!available.contains(slot)) {
                        conflicts.add(String.format("Courtroom Unavailable: Room %s is not open Slot %d on %s (Case %s)",
                                room.getRoomName(), slot, s1.getDate(), s1.getCaseId()));
                    }
                }
                if (c != null && c.requiresHighSecurity() && !room.isHighSecurity()) {
                    conflicts.add(String.format("Security Violation: Case %s requires High Security, but %s is standard",
                            c.getCaseId(), room.getRoomName()));
                }
            }
        }

        return conflicts;
    }

    /**
     * Validates an assignment before confirming. Throws SchedulingConflictException if invalid.
     */
    public void validateAssignment(Case c, Judge judge, Courtroom courtroom,
                                   String date, int startSlot, int duration,
                                   List<Schedule> existingSchedules, String ignoreScheduleId,
                                   int maxDailySlots) throws SchedulingConflictException {
        int endSlot = startSlot + duration - 1;

        if (startSlot < 1 || endSlot > maxDailySlots) {
            throw new SchedulingConflictException(String.format(
                    "Requested slot range (%d - %d) is outside court hours (1 to %d).", startSlot, endSlot, maxDailySlots));
        }

        if (judge.getSpecialization() != c.getRequiredSpecialization()) {
            throw new SchedulingConflictException(String.format(
                    "Judge %s (%s) does not match required specialization %s for Case %s.",
                    judge.getName(), judge.getSpecialization().getDisplayName(),
                    c.getRequiredSpecialization().getDisplayName(), c.getCaseId()));
        }

        if (c.requiresHighSecurity() && !courtroom.isHighSecurity()) {
            throw new SchedulingConflictException(String.format(
                    "Case %s requires a high-security courtroom, but %s (%s) is standard security.",
                    c.getCaseId(), courtroom.getCourtroomId(), courtroom.getRoomName()));
        }

        Set<Integer> judgeWorking = judge.getAvailableSlotsForDate(date);
        for (int s = startSlot; s <= endSlot; s++) {
            if (!judgeWorking.contains(s)) {
                throw new SchedulingConflictException(String.format("Judge %s is not available on %s for Slot %d.", judge.getName(), date, s));
            }
        }

        Set<Integer> roomWorking = courtroom.getAvailableSlotsForDate(date);
        for (int s = startSlot; s <= endSlot; s++) {
            if (!roomWorking.contains(s)) {
                throw new SchedulingConflictException(String.format("Courtroom %s is not open on %s for Slot %d.", courtroom.getRoomName(), date, s));
            }
        }

        for (Schedule existing : existingSchedules) {
            if (ignoreScheduleId != null && existing.getScheduleId().equalsIgnoreCase(ignoreScheduleId)) {
                continue;
            }
            if (existing.getDate().equalsIgnoreCase(date)) {
                boolean slotOverlap = !(endSlot < existing.getStartSlot() || existing.getEndSlot() < startSlot);
                if (slotOverlap) {
                    if (existing.getJudgeId().equalsIgnoreCase(judge.getJudgeId())) {
                        throw new SchedulingConflictException(String.format(
                                "Judge conflict: Judge %s is already scheduled for Case %s during %s on %s.",
                                judge.getName(), existing.getCaseId(), existing.getSlotRangeDescription(), date));
                    }
                    if (existing.getCourtroomId().equalsIgnoreCase(courtroom.getCourtroomId())) {
                        throw new SchedulingConflictException(String.format(
                                "Courtroom conflict: Room %s is already booked for Case %s during %s on %s.",
                                courtroom.getCourtroomId(), existing.getCaseId(), existing.getSlotRangeDescription(), date));
                    }
                }
            }
        }
    }
}
