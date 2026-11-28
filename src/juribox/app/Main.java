package juribox.app;

import juribox.exception.CourtSchedulingException;
import juribox.exception.CourtSchedulingException.*;
import juribox.model.*;
import juribox.model.Case.*;
import juribox.service.*;

import java.util.*;

/*
  Module: Presentation Layer & Console Interactive Menu Interface
  Member: Lakshit
  Date: 21 November 2026 - 22 November 2026
  Topic: Console User Interface, Input Parsing, Exception Handling & Application Entry Point
 */
public class Main {
    private static final Scanner scanner = new Scanner(System.in);
    private static final CourtManager courtManager = new CourtManager("data");
    private static final String R = "\u001B[0m", B = "\u001B[1m", D = "\u001B[90m",
            C = "\u001B[94m", BC = "\u001B[93m", W = "\u001B[97m", G = "\u001B[92m", Y = "\u001B[93m", RD = "\u001B[91m";

    public static void main(String[] args) {
        String bar = C + "+============================================================+" + R;
        System.out.println("\n" + bar + "\n" + C + "| " + B + W + String.format("%10s%-48s", "", "JURIBOX  -  COURT CASE SCHEDULING SIMULATOR") + C + " |\n" + bar);
        System.out.print(BC + "Load pre-configured demo cases, judges, and rooms? (Y/N, default Y): " + R);
        if (!scanner.hasNextLine()) return;
        String initChoice = scanner.nextLine().trim();
        if (initChoice.isEmpty() || initChoice.equalsIgnoreCase("Y")) {
            courtManager.seedDemoData();
            System.out.println(G + "[OK] Demo dataset loaded (3 Judges, 3 Rooms, 6 Cases)." + R);
        }

        // [Lakshit - 21 Nov 2026] Interactive 10-Option Command Dispatcher Loop
        boolean running = true;
        while (running) {
            printMenu();
            System.out.print(BC + "> Enter choice (1-10): " + R);
            if (!scanner.hasNextLine()) break;

            String choiceStr = scanner.nextLine().trim();
            try {
                int choice = Integer.parseInt(choiceStr);
                switch (choice) {
                    case 1: handleAddCase(); break;
                    case 2: handleViewCases(); break;
                    case 3: handleAddJudge(); break;
                    case 4: handleAddCourtroom(); break;
                    case 5: handleViewResources(); break;
                    case 6: handleAutoSchedule(); break;
                    case 7: handleDailySchedule(); break;
                    case 8: handleReschedule(); break;
                    case 9: handleSimulation(); break;
                    case 10:
                        courtManager.saveData();
                        System.out.println(G + "[SAVED] Schedule exported to file and synchronized with MySQL database. Exiting. Goodbye!" + R);
                        running = false;
                        break;
                    default:
                        System.out.println(RD + "[ERROR] Please enter a number between 1 and 10." + R);
                }
            } catch (NumberFormatException e) {
                System.out.println(RD + "[ERROR] Invalid input. Enter a valid number." + R);
            } catch (Exception e) {
                System.out.println(RD + "[ERROR] " + e.getMessage() + R);
            }

            if (running) {
                System.out.print(D + "\nPress ENTER to continue..." + R);
                if (scanner.hasNextLine()) scanner.nextLine();
                else break;
            }
        }
    }

    private static void printMenu() {
        List<Case> all = courtManager.getAllCases();
        int[] dbCounts = courtManager.getDatabaseMenuCounts();
        int totalCases = (dbCounts != null) ? dbCounts[0] : all.size();
        int pendingCases = (dbCounts != null) ? dbCounts[1] : courtManager.getPendingCases().size();
        long scheduled = (dbCounts != null) ? dbCounts[2] : all.stream().filter(Case::isScheduled).count();
        int totalJudges = (dbCounts != null) ? dbCounts[3] : courtManager.getAllJudges().size();
        int totalRooms = (dbCounts != null) ? dbCounts[4] : courtManager.getAllCourtrooms().size();
        String bar = C + "+==================================================================+" + R;
        String sep = C + "+------------------------------------------------------------------+" + R;
        String stats = String.format("Cases: %d | Pending: %d | Scheduled: %d | Judges: %d | Rooms: %d",
                totalCases, pendingCases, scheduled, totalJudges, totalRooms);
        System.out.printf("\n%s\n%s| %s%-64s%s |\n%s| %s%-64s%s |\n%s\n", bar, C, B + W, "COURT SCHEDULER MENU", C, C, D, stats, C, sep);
        String[][] rows = {
                {" 1.", "Add New Case", " 6.", "Schedule Cases Automatically"},
                {" 2.", "View All Cases", " 7.", "View Daily Schedule"},
                {" 3.", "Add Judge", " 8.", "Reschedule a Case"},
                {" 4.", "Add Courtroom", " 9.", "Live Court Simulation"},
                {" 5.", "View Judges & Courtrooms", "10.", "Save & Exit"}
        };
        for (String[] r : rows) {
            System.out.printf(C + "| " + BC + "%s " + W + "%-26s" + C + "| " + BC + "%s " + W + "%-29s" + C + "|" + R + "\n", r[0], r[1], r[2], r[3]);
        }
        System.out.println(bar);
    }

    private static void handleAddCase() throws DuplicateEntityException {
        System.out.print(BC + "> Enter Case ID (e.g. C107): " + R);
        String id = scanner.nextLine().trim().toUpperCase();
        System.out.print(BC + "> Enter Case Title: " + R);
        String title = scanner.nextLine().trim();
        System.out.print(BC + "> Case Type (1. Civil, 2. Criminal, 3. Property): " + R);
        int t = Integer.parseInt(scanner.nextLine().trim());
        CaseType type = (t == 1) ? CaseType.CIVIL : (t == 2) ? CaseType.CRIMINAL : CaseType.PROPERTY;

        System.out.print(BC + "> Priority (1. High, 2. Medium, 3. Low): " + R);
        int p = Integer.parseInt(scanner.nextLine().trim());
        Priority priority = (p == 1) ? Priority.HIGH : (p == 2) ? Priority.MEDIUM : Priority.LOW;

        System.out.print(BC + "> Duration in slots (1-6): " + R);
        int dur = Integer.parseInt(scanner.nextLine().trim());

        Case c;
        if (type == CaseType.CIVIL) {
            System.out.print(BC + "> Claim amount ($): " + R);
            c = new CivilCase(id, title, priority, dur, Double.parseDouble(scanner.nextLine().trim()), false);
        } else if (type == CaseType.CRIMINAL) {
            System.out.print(BC + "> Is accused in custody? (Y/N): " + R);
            boolean inCustody = scanner.nextLine().trim().equalsIgnoreCase("Y");
            c = new CriminalCase(id, title, priority, dur, inCustody, inCustody);
        } else {
            System.out.print(BC + "> Property location: " + R);
            c = new PropertyCase(id, title, priority, dur, scanner.nextLine().trim(), false);
        }
        courtManager.addCase(c);
        System.out.println(G + "[OK] Case " + id + " registered as PENDING." + R);
    }

    private static void handleViewCases() {
        System.out.println("\n" + B + BC + ">> ALL REGISTERED CASES" + R);
        List<Case> cases = courtManager.getAllCases();
        if (cases.isEmpty()) { System.out.println(D + "No cases registered." + R); return; }
        String line = C + "+--------+----------------------------+----------+----------+-------+-------------+---------------+--------------+" + R;
        System.out.println(line);
        System.out.printf(C + "| " + B + W + "%-6s " + C + "| " + B + W + "%-26s " + C + "| " + B + W + "%-8s " + C + "| " + B + W + "%-8s " + C + "| " + B + W + "%-5s " + C + "| " + B + W + "%-11s " + C + "| " + B + W + "%-13s " + C + "| " + B + W + "%-12s " + C + "|" + R + "\n",
                "ID", "Title", "Type", "Priority", "Slots", "Status", "Schedule", "Hearing Date");
        System.out.println(line);
        for (Case c : cases) {
            String title = c.getTitle().length() > 26 ? c.getTitle().substring(0, 23) + "..." : c.getTitle();
            String pc = c.getPriority() == Priority.HIGH ? RD : c.getPriority() == Priority.MEDIUM ? Y : G;
            String sc = c.getStatus() == CaseStatus.PENDING ? Y : c.getStatus() == CaseStatus.SCHEDULED ? G : "\u001B[96m";
            String sched = c.getAssignedScheduleId() != null ? c.getAssignedScheduleId() : "-";
            String rawDate = courtManager.getHearingDateForCase(c.getCaseId());
            String hearingDate = rawDate != null ? rawDate : "--";
            System.out.printf(C + "| " + B + W + "%-6s " + C + "| " + R + "%-26s " + C + "| " + R + "%-8s " + C + "| " + pc + "%-8s " + C + "| " + R + "%-5d " + C + "| " + sc + "%-11s " + C + "| " + (sched.equals("-") ? D : BC) + "%-13s " + C + "| " + (hearingDate.equals("--") ? D : BC) + "%-12s " + C + "|" + R + "\n",
                    c.getCaseId(), title, c.getCaseType().getDisplayName(), c.getPriority().getDisplayName(),
                    c.getDurationSlots(), c.getStatus().name(), sched, hearingDate);
        }
        System.out.println(line);
    }

    private static void handleAddJudge() throws DuplicateEntityException {
        System.out.print(BC + "> Enter Judge ID (e.g. J04): " + R);
        String id = scanner.nextLine().trim().toUpperCase();
        System.out.print(BC + "> Enter Judge Full Name: " + R);
        String name = scanner.nextLine().trim();
        System.out.print(BC + "> Specialization (1. Civil, 2. Criminal, 3. Property): " + R);
        int s = Integer.parseInt(scanner.nextLine().trim());
        CaseType spec = (s == 1) ? CaseType.CIVIL : (s == 2) ? CaseType.CRIMINAL : CaseType.PROPERTY;
        courtManager.addJudge(new Judge(id, name, spec));
        System.out.println(G + "[OK] Judge " + name + " (" + id + ") registered in " + spec.getDisplayName() + "." + R);
    }

    private static void handleAddCourtroom() throws DuplicateEntityException {
        System.out.print(BC + "> Enter Courtroom ID (e.g. CR04): " + R);
        String id = scanner.nextLine().trim().toUpperCase();
        System.out.print(BC + "> Enter Room Name/Number: " + R);
        String name = scanner.nextLine().trim();
        System.out.print(BC + "> Is this a High-Security courtroom? (Y/N): " + R);
        courtManager.addCourtroom(new Courtroom(id, name, scanner.nextLine().trim().equalsIgnoreCase("Y")));
        System.out.println(G + "[OK] Courtroom " + id + " registered." + R);
    }

    private static void handleViewResources() {
        System.out.println("\n" + B + BC + ">> JUDGES" + R);
        String jLine = C + "+--------+--------------------------+----------------+--------------+" + R;
        System.out.println(jLine + "\n" + String.format(C + "| " + B + W + "%-6s " + C + "| " + B + W + "%-24s " + C + "| " + B + W + "%-14s " + C + "| " + B + W + "%-12s " + C + "|" + R, "ID", "Name", "Specialization", "Active Cases") + "\n" + jLine);
        for (Judge j : courtManager.getAllJudges()) {
            System.out.printf(C + "| " + B + W + "%-6s " + C + "| " + R + "%-24s " + C + "| " + R + "%-14s " + C + "| " + R + "%-12d " + C + "|" + R + "\n",
                    j.getJudgeId(), j.getName(), j.getSpecialization().getDisplayName(), j.getAssignedScheduleIds().size());
        }
        System.out.println(jLine + "\n\n" + B + BC + ">> COURTROOMS" + R);
        String rLine = C + "+--------+------------------------------+---------------+--------------+" + R;
        System.out.println(rLine + "\n" + String.format(C + "| " + B + W + "%-6s " + C + "| " + B + W + "%-28s " + C + "| " + B + W + "%-13s " + C + "| " + B + W + "%-12s " + C + "|" + R, "ID", "Room Name", "Security", "Active Cases") + "\n" + rLine);
        for (Courtroom cr : courtManager.getAllCourtrooms()) {
            System.out.printf(C + "| " + B + W + "%-6s " + C + "| " + R + "%-28s " + C + "| " + (cr.isHighSecurity() ? RD : G) + "%-13s " + C + "| " + R + "%-12d " + C + "|" + R + "\n",
                    cr.getCourtroomId(), cr.getRoomName(), cr.isHighSecurity() ? "High Security" : "Standard", cr.getAssignedScheduleIds().size());
        }
        System.out.println(rLine);
    }

    private static void handleAutoSchedule() {
        System.out.println("\n" + B + BC + ">> AUTO-SCHEDULER" + R + "\n" + D + "Running auto-scheduler..." + R);
        List<Schedule> scheduled = courtManager.schedulePendingCases();
        System.out.printf(G + "[OK] Successfully scheduled %d case(s)!" + R + "\n", scheduled.size());
        for (Schedule s : scheduled) System.out.println("  " + BC + s + R);

        List<String> conflicts = courtManager.detectConflicts();
        if (conflicts.isEmpty()) {
            System.out.println(G + "[OK] [Internal Check] 0 scheduling conflicts detected. All assignments are verified safe." + R);
        } else {
            System.out.printf(RD + "[WARN] [Internal Check] %d conflict(s) detected:\n" + R, conflicts.size());
            for (String err : conflicts) System.out.println(RD + "  - " + err + R);
        }
        System.out.print(courtManager.getFormattedStatistics());
    }

    private static void handleDailySchedule() {
        System.out.print(BC + "> Enter Date (YYYY-MM-DD, e.g. 2026-11-10): " + R);
        String date = scanner.nextLine().trim();
        if (date.isEmpty()) date = "2026-11-10";
        System.out.print(courtManager.formatDailySchedule(date));
        System.out.print(courtManager.getFormattedStatistics());
    }

    private static void handleReschedule() throws EntityNotFoundException, CourtSchedulingException {
        System.out.print(BC + "> Enter Case ID to reschedule: " + R);
        String caseId = scanner.nextLine().trim().toUpperCase();
        System.out.print(BC + "> Enter Target Date (YYYY-MM-DD): " + R);
        String date = scanner.nextLine().trim();
        System.out.print(BC + "> Enter New Start Slot (1-6): " + R);
        Schedule updated = courtManager.rescheduleCase(caseId, date, Integer.parseInt(scanner.nextLine().trim()), null, null);
        System.out.println(G + "[OK] Case rescheduled: " + updated + R);
    }

    private static void handleSimulation() {
        System.out.print(BC + "> Enter date to simulate (default 2026-11-10): " + R);
        String date = scanner.nextLine().trim();
        if (date.isEmpty()) date = "2026-11-10";
        courtManager.simulateCourtDay(date, 400);
    }
}
