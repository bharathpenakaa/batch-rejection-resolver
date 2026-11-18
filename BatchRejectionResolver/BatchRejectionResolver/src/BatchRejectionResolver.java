import java.util.*;
import java.util.stream.*;

/**
 * Batch Rejection Resolver
 * ------------------------
 * Parses simplified 277CA-style claim rejection responses for a batch,
 * classifies each rejection's root cause (keyword-weighted scoring),
 * recommends a fix, and flags which rejections can be auto-corrected.
 *
 * Requires Java 17+. No external dependencies.
 * Compile: javac BatchRejectionResolver.java
 * Run:     java BatchRejectionResolver
 */
public class BatchRejectionResolver {

    // ---------- Domain model ----------

    enum Cause {
        MISSING_DATA("Add the missing field (e.g., subscriber ID, DOS) from the source record and resubmit."),
        INVALID_FORMAT("Normalize date/ID formatting (CCYYMMDD, no special characters) and resubmit."),
        ELIGIBILITY("Run a 270/271 eligibility check, correct member/payer details, then resubmit."),
        DUPLICATE("Verify the original claim status (276/277); void or submit as a corrected claim if needed."),
        INVALID_CODE("Validate CPT/ICD-10/modifier combination against the current code set and correct."),
        PROVIDER_ID("Verify NPI/Tax ID and enrollment with the payer, then correct the billing provider loop."),
        TIMELY_FILING("Escalate immediately: attach proof of timely filing and request an exception."),
        UNKNOWN("Route to a claims analyst for manual review.");

        final String action;
        Cause(String action) { this.action = action; }
    }

    record Rejection(String batchId, String claimId, String statusCode, String message) {}

    record Resolution(Rejection rejection, Cause cause, double confidence,
                      String action, boolean autoFixable) {}

    // ---------- Parser (simplified 277CA segments, '~' terminated) ----------

    static List<Rejection> parse(String edi) {
        List<Rejection> out = new ArrayList<>();
        String batch = "UNKNOWN", claim = null, status = "", msg = "";

        for (String raw : edi.split("~")) {
            String seg = raw.trim();
            if (seg.isEmpty()) continue;
            String[] f = seg.split("\\*");

            switch (f[0]) {
                case "TRN" -> {
                    if (claim != null) out.add(new Rejection(batch, claim, status, msg));
                    claim = null; status = ""; msg = "";
                    batch = f.length > 2 ? f[2] : "UNKNOWN";
                }
                case "REF" -> {
                    if (f.length > 2 && f[1].equals("1K")) {
                        if (claim != null) out.add(new Rejection(batch, claim, status, msg));
                        claim = f[2]; status = ""; msg = "";
                    }
                }
                case "STC" -> { if (f.length > 1) status = f[1]; }
                case "MSG" -> { if (f.length > 1) msg = f[1]; }
                default -> { /* ignore other segments */ }
            }
        }
        if (claim != null) out.add(new Rejection(batch, claim, status, msg));
        return out;
    }

    // ---------- Classifier ----------

    private static final Map<Cause, Map<String, Double>> KEYWORDS = new EnumMap<>(Cause.class);
    static {
        KEYWORDS.put(Cause.MISSING_DATA, Map.of(
                "missing", 2.0, "required", 1.5, "not provided", 2.0, "blank", 1.5, "absent", 1.0));
        KEYWORDS.put(Cause.INVALID_FORMAT, Map.of(
                "invalid format", 2.5, "format", 1.5, "malformed", 2.0, "date", 1.0, "length", 1.0, "character", 1.0));
        KEYWORDS.put(Cause.ELIGIBILITY, Map.of(
                "eligib", 2.5, "not covered", 2.0, "inactive", 2.0, "member not found", 2.5, "coverage", 1.5, "terminated", 1.5));
        KEYWORDS.put(Cause.DUPLICATE, Map.of(
                "duplicate", 3.0, "already", 1.5, "previously submitted", 2.5, "resubmission", 1.0));
        KEYWORDS.put(Cause.INVALID_CODE, Map.of(
                "cpt", 2.0, "icd", 2.0, "diagnosis", 1.5, "procedure code", 2.5, "modifier", 2.0, "invalid code", 2.5));
        KEYWORDS.put(Cause.PROVIDER_ID, Map.of(
                "npi", 3.0, "provider", 1.5, "tax id", 2.5, "taxonomy", 2.0, "not enrolled", 2.5, "billing provider", 2.0));
        KEYWORDS.put(Cause.TIMELY_FILING, Map.of(
                "timely filing", 3.5, "filing limit", 3.0, "past deadline", 2.5, "exceeds", 1.0));
    }

    // Prior from 277CA category code prefix (e.g., A3 = returned as unprocessable)
    private static double statusBoost(String statusCode, Cause cause) {
        if (statusCode.startsWith("A3") && cause == Cause.MISSING_DATA) return 0.5;
        if (statusCode.startsWith("A7") && cause == Cause.INVALID_FORMAT) return 0.5;
        if (statusCode.startsWith("A8") && cause == Cause.INVALID_CODE) return 0.5;
        return 0.0;
    }

    static Resolution classify(Rejection r) {
        String text = r.message().toLowerCase();
        Map<Cause, Double> scores = new EnumMap<>(Cause.class);
        double total = 0;

        for (var entry : KEYWORDS.entrySet()) {
            double s = 0;
            for (var kw : entry.getValue().entrySet()) {
                if (text.contains(kw.getKey())) s += kw.getValue();
            }
            if (s > 0) s += statusBoost(r.statusCode(), entry.getKey());
            scores.put(entry.getKey(), s);
            total += s;
        }

        Cause best = Cause.UNKNOWN;
        double bestScore = 0;
        for (var e : scores.entrySet()) {
            if (e.getValue() > bestScore) { best = e.getKey(); bestScore = e.getValue(); }
        }

        double confidence = total == 0 ? 0.0 : bestScore / total;
        if (confidence < 0.4) best = Cause.UNKNOWN; // too ambiguous

        boolean auto = confidence >= 0.7
                && (best == Cause.INVALID_FORMAT || best == Cause.DUPLICATE);
        return new Resolution(r, best, confidence, best.action, auto);
    }

    // ---------- Helper: NPI check (Luhn with 80840 prefix) ----------

    static boolean isValidNpi(String npi) {
        if (npi == null || !npi.matches("\\d{10}")) return false;
        String s = "80840" + npi;
        int sum = 0;
        boolean dbl = false;
        for (int i = s.length() - 1; i >= 0; i--) {
            int d = s.charAt(i) - '0';
            if (dbl) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    // ---------- Helper: date normalization to CCYYMMDD ----------

    static Optional<String> normalizeDate(String input) {
        if (input == null) return Optional.empty();
        String d = input.trim();
        if (d.matches("\\d{8}")) return Optional.of(d);
        if (d.matches("\\d{4}-\\d{2}-\\d{2}")) return Optional.of(d.replace("-", ""));
        if (d.matches("\\d{2}/\\d{2}/\\d{4}")) {
            String[] p = d.split("/");
            return Optional.of(p[2] + p[0] + p[1]);
        }
        return Optional.empty();
    }

    // ---------- Reporting ----------

    static void report(List<Resolution> results) {
        Map<String, List<Resolution>> byBatch = results.stream()
                .collect(Collectors.groupingBy(x -> x.rejection().batchId(), TreeMap::new, Collectors.toList()));

        for (var batch : byBatch.entrySet()) {
            List<Resolution> list = batch.getValue();
            System.out.printf("%n=== Batch %s : %d rejection(s) ===%n", batch.getKey(), list.size());

            Map<Cause, Long> counts = list.stream()
                    .collect(Collectors.groupingBy(Resolution::cause, () -> new EnumMap<>(Cause.class), Collectors.counting()));
            counts.forEach((c, n) -> System.out.printf("  %-15s : %d%n", c, n));

            long autoCount = list.stream().filter(Resolution::autoFixable).count();
            System.out.printf("  Auto-fixable    : %d of %d%n", autoCount, list.size());

            System.out.println("  --- Work queue (priority order) ---");
            list.stream()
                .sorted(Comparator
                        .comparing((Resolution x) -> x.cause() != Cause.TIMELY_FILING)   // urgent first
                        .thenComparing(x -> !x.autoFixable())                             // quick wins next
                        .thenComparing(Comparator.comparingDouble(Resolution::confidence).reversed()))
                .forEach(x -> System.out.printf(
                        "  [%s] Claim %-8s %-14s conf=%.2f auto=%s%n      -> %s%n",
                        x.rejection().statusCode(), x.rejection().claimId(), x.cause(),
                        x.confidence(), x.autoFixable() ? "Y" : "N", x.action()));
        }
    }

    // ---------- Demo ----------

    public static void main(String[] args) {
        String sample =
                "TRN*2*BATCH001~" +
                "REF*1K*CLM1001~STC*A3:21*20261110~MSG*Subscriber ID is missing~" +
                "REF*1K*CLM1002~STC*A7:187*20261110~MSG*Invalid format for service date~" +
                "REF*1K*CLM1003~STC*A3:33*20261110~MSG*Duplicate claim previously submitted~" +
                "TRN*2*BATCH002~" +
                "REF*1K*CLM2001~STC*A8:255*20261111~MSG*Invalid CPT code and modifier combination~" +
                "REF*1K*CLM2002~STC*A3:45*20261111~MSG*Billing provider NPI not enrolled~" +
                "REF*1K*CLM2003~STC*A3:21*20261111~MSG*Claim exceeds timely filing limit~" +
                "REF*1K*CLM2004~STC*A3:99*20261111~MSG*Member not found, coverage inactive~" +
                "REF*1K*CLM2005~STC*A3:00*20261111~MSG*Unspecified payer error~";

        List<Rejection> rejections = parse(sample);
        List<Resolution> results = rejections.stream()
                .map(BatchRejectionResolver::classify)
                .collect(Collectors.toList());

        report(results);

        System.out.println("\n--- Helper checks ---");
        System.out.println("NPI 1234567893 valid? " + isValidNpi("1234567893"));
        System.out.println("Normalize 11/10/2026 -> " + normalizeDate("11/10/2026").orElse("N/A"));
    }
}
