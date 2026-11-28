package juribox.service;

import juribox.exception.CourtSchedulingException.*;
import juribox.model.*;
import juribox.model.Case.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;

/*
  Module: Central CourtManager Orchestration Service & File Stream Persistence
  Member: Tanish
  Date: 16 November 2026 - 18 November 2026
  Topic: Facade Pattern, Resource Coordination, Byte and Character Streams (FileOutputStream/FileInputStream)
 */
public class CourtManager {
    public static final int MAX_DAILY_SLOTS = 6;

    private final List<Case> cases;
    private final Map<String, Case> caseMap;
    private final Map<String, Judge> judgeMap;
    private final Map<String, Courtroom> courtroomMap;
    private final List<Schedule> schedules;
    private final List<String> courtDates;

    private final Scheduler scheduler;
    private final ConflictChecker conflictChecker;
    private final String exportFilePath;
    private final DatabaseManager databaseManager;
    private final CourtSimulation courtSimulation;

    public CourtManager(String dataDirectory) {
        this.cases = new ArrayList<>();
        this.caseMap = new HashMap<>();
        this.judgeMap = new LinkedHashMap<>();
        this.courtroomMap = new LinkedHashMap<>();
        this.schedules = new ArrayList<>();
        this.courtDates = new ArrayList<>();

        this.conflictChecker = new ConflictChecker();
        this.scheduler = new Scheduler(conflictChecker);
        File dir = new File(dataDirectory);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.exportFilePath = dataDirectory + "/schedule_export.txt";
        this.databaseManager = new DatabaseManager(dataDirectory);
        this.courtSimulation = new CourtSimulation();

        this.courtDates.add("2026-11-10");
        this.courtDates.add("2026-11-11");
        this.courtDates.add("2026-11-12");
    }

    // --- Case Management ---

    public void addCase(Case caseObj) throws DuplicateEntityException {
        if (caseMap.containsKey(caseObj.getCaseId())) {
            throw new DuplicateEntityException("Case with ID '" + caseObj.getCaseId() + "' already exists.");
        }
        cases.add(caseObj);
        caseMap.put(caseObj.getCaseId(), caseObj);
    }

    public List<Case> getAllCases() {
        return Collections.unmodifiableList(cases);
    }

    public List<Case> getPendingCases() {
        List<Case> pending = new ArrayList<>();
        for (Case c : cases) {
            if (c.getStatus() == CaseStatus.PENDING) {
                pending.add(c);
            }
        }
        return pending;
    }

    public Case searchCase(String caseId) throws EntityNotFoundException {
        Case c = caseMap.get(caseId.trim().toUpperCase());
        if (c == null) {
            throw new EntityNotFoundException("Case with ID '" + caseId + "' not found.");
        }
        return c;
    }

    // --- Judge & Courtroom Management ---

    public void addJudge(Judge judge) throws DuplicateEntityException {
        if (judgeMap.containsKey(judge.getJudgeId())) {
            throw new DuplicateEntityException("Judge with ID '" + judge.getJudgeId() + "' already exists.");
        }
        judgeMap.put(judge.getJudgeId(), judge);
        for (String date : courtDates) {
            if (judge.getAvailableSlotsForDate(date).isEmpty()) {
                judge.setDefaultAvailability(date, MAX_DAILY_SLOTS);
            }
        }
    }

    public List<Judge> getAllJudges() {
        return new ArrayList<>(judgeMap.values());
    }

    public Judge searchJudge(String judgeId) throws EntityNotFoundException {
        Judge j = judgeMap.get(judgeId.trim().toUpperCase());
        if (j == null) {
            throw new EntityNotFoundException("Judge with ID '" + judgeId + "' not found.");
        }
        return j;
    }

    public void addCourtroom(Courtroom courtroom) throws DuplicateEntityException {
        if (courtroomMap.containsKey(courtroom.getCourtroomId())) {
            throw new DuplicateEntityException(
                    "Courtroom with ID '" + courtroom.getCourtroomId() + "' already exists.");
        }
        courtroomMap.put(courtroom.getCourtroomId(), courtroom);
        for (String date : courtDates) {
            if (courtroom.getAvailableSlotsForDate(date).isEmpty()) {
                courtroom.setDefaultAvailability(date, MAX_DAILY_SLOTS);
            }
        }
    }

    public List<Courtroom> getAllCourtrooms() {
        return new ArrayList<>(courtroomMap.values());
    }

    public Courtroom searchCourtroom(String courtroomId) throws EntityNotFoundException {
        Courtroom cr = courtroomMap.get(courtroomId.trim().toUpperCase());
        if (cr == null) {
            throw new EntityNotFoundException("Courtroom with ID '" + courtroomId + "' not found.");
        }
        return cr;
    }

    // --- Scheduling Operations ---

    public List<Schedule> schedulePendingCases() {
        return scheduler.schedulePendingCases(cases, judgeMap, courtroomMap, schedules, courtDates, MAX_DAILY_SLOTS);
    }

    public Schedule rescheduleCase(String caseId, String newDate, int newStartSlot,
            String targetJudgeId, String targetCourtroomId)
            throws EntityNotFoundException, SchedulingConflictException {
        Case c = searchCase(caseId);
        Schedule oldSchedule = null;
        for (Schedule s : schedules) {
            if (s.getCaseId().equalsIgnoreCase(caseId)) {
                oldSchedule = s;
                break;
            }
        }

        if (oldSchedule == null) {
            throw new EntityNotFoundException("Cannot reschedule: No existing schedule found for Case " + caseId);
        }

        Judge oldJudge = judgeMap.get(oldSchedule.getJudgeId());
        Courtroom oldRoom = courtroomMap.get(oldSchedule.getCourtroomId());

        Judge judge = (targetJudgeId != null && !targetJudgeId.isEmpty())
                ? searchJudge(targetJudgeId)
                : oldJudge;
        Courtroom room = (targetCourtroomId != null && !targetCourtroomId.isEmpty())
                ? searchCourtroom(targetCourtroomId)
                : oldRoom;

        int duration = c.getDurationSlots();

        conflictChecker.validateAssignment(
                c, judge, room, newDate, newStartSlot, duration,
                schedules, oldSchedule.getScheduleId(), MAX_DAILY_SLOTS);

        if (oldJudge != null) {
            oldJudge.releaseSlots(oldSchedule.getDate(), oldSchedule.getStartSlot(), oldSchedule.getDurationSlots(),
                    oldSchedule.getScheduleId());
        }
        if (oldRoom != null) {
            oldRoom.releaseSlots(oldSchedule.getDate(), oldSchedule.getStartSlot(), oldSchedule.getDurationSlots(),
                    oldSchedule.getScheduleId());
        }

        oldSchedule.setDate(newDate);
        oldSchedule.setStartSlot(newStartSlot);
        oldSchedule.setJudgeId(judge.getJudgeId());
        oldSchedule.setCourtroomId(room.getCourtroomId());

        judge.bookSlots(newDate, newStartSlot, duration, oldSchedule.getScheduleId());
        room.bookSlots(newDate, newStartSlot, duration, oldSchedule.getScheduleId());

        c.setStatus(CaseStatus.RESCHEDULED);

        return oldSchedule;
    }

    // --- Daily Schedule & Reporting ---

    public List<Schedule> getDailySchedule(String date) {
        List<Schedule> daily = new ArrayList<>();
        for (Schedule s : schedules) {
            if (s.getDate().equals(date)) {
                daily.add(s);
            }
        }
        daily.sort(Comparator.comparingInt(Schedule::getStartSlot));
        return daily;
    }

    private static final String R = "\u001B[0m", B = "\u001B[1m", D = "\u001B[90m",
            C = "\u001B[94m", BC = "\u001B[93m", W = "\u001B[97m", G = "\u001B[92m", Y = "\u001B[93m",
            RD = "\u001B[91m";

    public String formatDailySchedule(String date) {
        List<Schedule> daily = getDailySchedule(date);
        String line = C
                + "+--------------------+----------+------------+----------------------+-----------------------------+----------+"
                + R + "\n";
        StringBuilder sb = new StringBuilder("\n" + B + BC + ">> COURT DAILY SCHEDULE: " + date + R + "\n")
                .append(line);
        sb.append(String.format(
                C + "| " + B + W + "%-18s " + C + "| " + B + W + "%-8s " + C + "| " + B + W + "%-10s " + C + "| " + B
                        + W + "%-20s " + C + "| " + B + W + "%-27s " + C + "| " + B + W + "%-8s " + C + "|" + R + "\n",
                "Time Slot", "Case ID", "Case Type", "Judge", "Courtroom", "Priority")).append(line);
        if (daily.isEmpty()) {
            sb.append(C + "| " + D + String.format("%-104s", "No hearings scheduled for this date.") + C + " |" + R
                    + "\n");
        } else {
            for (Schedule s : daily) {
                Case c = caseMap.get(s.getCaseId());
                Judge j = judgeMap.get(s.getJudgeId());
                Courtroom cr = courtroomMap.get(s.getCourtroomId());
                String type = (c != null) ? c.getCaseType().getDisplayName() : "N/A";
                String pri = (c != null) ? c.getPriority().getDisplayName() : "N/A";
                String pc = "High".equals(pri) ? RD : "Medium".equals(pri) ? Y : "Low".equals(pri) ? G : "";
                String jName = (j != null) ? j.getName() : s.getJudgeId();
                String rName = (cr != null) ? cr.getRoomName() : s.getCourtroomId();
                if (jName.length() > 20)
                    jName = jName.substring(0, 17) + "...";
                if (rName.length() > 27)
                    rName = rName.substring(0, 24) + "...";
                sb.append(String.format(
                        C + "| " + R + "%-18s " + C + "| " + B + W + "%-8s " + C + "| " + R + "%-10s " + C + "| " + R
                                + "%-20s " + C + "| " + R + "%-27s " + C + "| " + pc + "%-8s " + C + "|" + R + "\n",
                        s.getSlotRangeDescription(), s.getCaseId(), type, jName, rName, pri));
            }
        }
        return sb.append(line).toString();
    }

    public List<String> detectConflicts() {
        return conflictChecker.checkAllConflicts(caseMap, judgeMap, courtroomMap, schedules, MAX_DAILY_SLOTS);
    }

    public String getFormattedStatistics() {
        int totalCases = cases.size(), scheduled = 0, pending = 0, rescheduled = 0, urgentTotal = 0, urgentDone = 0;
        for (Case c : cases) {
            if (c.getStatus() == CaseStatus.SCHEDULED)
                scheduled++;
            else if (c.getStatus() == CaseStatus.PENDING)
                pending++;
            else if (c.getStatus() == CaseStatus.RESCHEDULED)
                rescheduled++;
            if (c.getPriority() == Priority.HIGH) {
                urgentTotal++;
                if (c.isScheduled())
                    urgentDone++;
            }
        }
        int totalPossibleJudgeSlots = judgeMap.size() * courtDates.size() * MAX_DAILY_SLOTS, totalBookedJudgeSlots = 0;
        for (Judge j : judgeMap.values())
            for (String d : courtDates)
                totalBookedJudgeSlots += j.getBookedSlotsForDate(d).size();
        double jUtil = totalPossibleJudgeSlots > 0 ? (totalBookedJudgeSlots * 100.0 / totalPossibleJudgeSlots) : 0.0;

        int totalPossibleRoomSlots = courtroomMap.size() * courtDates.size() * MAX_DAILY_SLOTS,
                totalBookedRoomSlots = 0;
        for (Courtroom cr : courtroomMap.values())
            for (String d : courtDates)
                totalBookedRoomSlots += cr.getBookedSlotsForDate(d).size();
        double crUtil = totalPossibleRoomSlots > 0 ? (totalBookedRoomSlots * 100.0 / totalPossibleRoomSlots) : 0.0;

        List<String> conflicts = detectConflicts();
        String bar = C + "+====================================================================+" + R + "\n";
        String sep = C + "+--------------------------------------------------------------------+" + R + "\n";
        return new StringBuilder("\n" + bar)
                .append(C + "| " + B + W + String.format("%-66s", "COURT SCHEDULING STATISTICS") + C + " |" + R + "\n")
                .append(sep)
                .append(C + "| " + W
                        + String.format("Total Cases        : %d %s(Scheduled: %d, Pending: %d, Rescheduled: %d)",
                                totalCases, D, scheduled, pending, rescheduled)
                        + " ".repeat(
                                Math.max(0,
                                        66 - String.format(
                                                "Total Cases        : %d (Scheduled: %d, Pending: %d, Rescheduled: %d)",
                                                totalCases, scheduled, pending, rescheduled).length()))
                        + C + " |" + R + "\n")
                .append(C + "| " + R
                        + String.format("High Priority Cases: %s%d / %d%s scheduled%s", BC, urgentDone, urgentTotal, R,
                                " ".repeat(Math.max(0, 32 - String.valueOf(urgentDone).length() - String.valueOf(urgentTotal).length())))
                        + C + " |" + R + "\n")
                .append(C + "| " + R
                        + String.format("Detected Conflicts : %s%-45d", (conflicts.isEmpty() ? G : RD),
                                conflicts.size())
                        + C + " |" + R + "\n")
                .append(C + "| " + R
                        + String.format("Judge Utilization  : %s  %s%5.1f%%%s%-15s", bar(jUtil), W, jUtil, R, "") + C
                        + " |" + R + "\n")
                .append(C + "| " + R
                        + String.format("Courtroom Util.    : %s  %s%5.1f%%%s%-15s", bar(crUtil), W, crUtil, R, "") + C
                        + " |" + R + "\n")
                .append(bar).toString();
    }

    private static String bar(double pct) {
        int f = (int) Math.round(Math.max(0, Math.min(100, pct)) / 5.0);
        return D + "[" + (pct >= 90 ? RD : pct >= 70 ? Y : G) + "#".repeat(f) + D + "-".repeat(20 - f) + "]" + R;
    }

    // --- Multithreaded Court Session Simulation ---

    public void simulateCourtDay(String date, int delayMs) {
        courtSimulation.simulateDaySessions(
                date, getAllCourtrooms(), new ArrayList<>(schedules),
                new HashMap<>(caseMap), new HashMap<>(judgeMap), delayMs);
    }

    // --- Persistence (File Streams & JDBC Database) ---

    public synchronized void saveData() throws IOException {
        exportSchedule(new ArrayList<>(schedules));
        try {
            saveToDatabase();
        } catch (SQLException e) {
            System.err.println("[JDBC WARNING] Database sync skipped: " + e.getMessage());
        }
    }

    public synchronized void loadData() throws IOException {
        try {
            loadFromDatabase();
        } catch (SQLException e) {
            System.err.println("[DATABASE LOAD WARNING] " + e.getMessage());
        }
    }

    /**
     * Exports confirmed schedules using FileOutputStream and PrintWriter (Byte +
     * Character stream).
     */
    public void exportSchedule(List<Schedule> scheduleList) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(exportFilePath);
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
            writer.println("================================================================================");
            writer.println("                          OFFICIAL COURT SCHEDULE EXPORT                        ");
            writer.println("================================================================================");
            for (Schedule s : scheduleList) {
                writer.printf("[%s] Date: %s | %-18s | Case: %-6s | Judge: %-5s | Room: %-5s%n",
                        s.getScheduleId(), s.getDate(), s.getSlotRangeDescription(),
                        s.getCaseId(), s.getJudgeId(), s.getCourtroomId());
            }
            writer.println("================================================================================");
        }
    }

    /**
     * Reads exported schedule using FileInputStream and BufferedReader.
     */
    public String readExportedSchedule() throws IOException {
        File file = new File(exportFilePath);
        if (!file.exists()) {
            return "No export file found at " + exportFilePath;
        }

        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(file);
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    public synchronized void saveToDatabase() throws SQLException {
        databaseManager.saveAllToDatabase(cases, judgeMap.values(), courtroomMap.values(), schedules);
    }

    public synchronized void loadFromDatabase() throws SQLException {
        cases.clear();
        caseMap.clear();
        judgeMap.clear();
        courtroomMap.clear();
        schedules.clear();

        for (Case c : databaseManager.loadCases()) {
            cases.add(c);
            caseMap.put(c.getCaseId(), c);
        }
        for (Judge j : databaseManager.loadJudges()) {
            judgeMap.put(j.getJudgeId(), j);
            for (String d : courtDates)
                j.setDefaultAvailability(d, MAX_DAILY_SLOTS);
        }
        for (Courtroom cr : databaseManager.loadCourtrooms()) {
            courtroomMap.put(cr.getCourtroomId(), cr);
            for (String d : courtDates)
                cr.setDefaultAvailability(d, MAX_DAILY_SLOTS);
        }
        for (Schedule s : databaseManager.loadSchedules()) {
            schedules.add(s);
            Judge j = judgeMap.get(s.getJudgeId());
            if (j != null)
                j.bookSlots(s.getDate(), s.getStartSlot(), s.getDurationSlots(), s.getScheduleId());
            Courtroom cr = courtroomMap.get(s.getCourtroomId());
            if (cr != null)
                cr.bookSlots(s.getDate(), s.getStartSlot(), s.getDurationSlots(), s.getScheduleId());
        }
    }

    // --- Demo Data Seeder ---

    public void seedDemoData() {
        try {
            addJudge(new Judge("J01", "Hon. Justice Miller", CaseType.CIVIL));
            addJudge(new Judge("J02", "Hon. Justice Vance", CaseType.CRIMINAL));
            addJudge(new Judge("J03", "Hon. Justice Thorne", CaseType.PROPERTY));

            addCourtroom(new Courtroom("CR01", "Tis Hazari Courtroom 1 (Civil)", false));
            addCourtroom(new Courtroom("CR02", "Patiala House High-Security Hall", true));
            addCourtroom(new Courtroom("CR03", "Saket District Annex Bench", false));

            addCase(new CivilCase("C101", "Mehta Textiles vs Kapoor Exports", Priority.MEDIUM, 1, 150_000, true));
            addCase(new CivilCase("C102", "NCDRC Consumer Refund Claim", Priority.LOW, 1, 8_500, false));
            addCase(new CriminalCase("C103", "State of Delhi vs Vikram Rathore", Priority.HIGH, 2, true, true));
            addCase(new CriminalCase("C104", "CBI vs Corporate Fraud Case", Priority.MEDIUM, 1, false, false));
            addCase(new PropertyCase("C105", "DDA Demolition Stay Petition", Priority.HIGH, 1, "Dwarka Sector 12", true));
            addCase(new PropertyCase("C106", "Ancestral Haveli Partition Suit", Priority.LOW, 2, "Jaipur Civil Lines", false));
        } catch (DuplicateEntityException ignored) {
        }
    }

    public List<String> getCourtDates() {
        return Collections.unmodifiableList(courtDates);
    }

    public int[] getDatabaseMenuCounts() {
        return databaseManager.getMenuCounts();
    }

    public String getHearingDateForCase(String caseId) {
        for (Schedule s : schedules) {
            if (s.getCaseId().equalsIgnoreCase(caseId)) {
                return s.getDate();
            }
        }
        return null;
    }
}
