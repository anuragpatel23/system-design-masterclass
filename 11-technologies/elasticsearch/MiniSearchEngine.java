import java.util.*;
import java.util.stream.Collectors;

/**
 * MiniSearchEngine — a Lucene-style search engine from scratch:
 *
 *   1. ANALYZER: tokenize -> lowercase -> stopword removal -> naive stemming.
 *      (Runs identically at index time and query time — the golden rule.)
 *   2. INVERTED INDEX: term -> posting list {docId -> term frequency}.
 *   3. RETRIEVAL: boolean AND via posting-list intersection.
 *   4. RANKING: TF-IDF (the ancestor of BM25) — frequent-in-doc,
 *      rare-in-corpus terms score highest.
 *   5. AUTOCOMPLETE: edge n-grams built at index time.
 *
 * Compile & run:  javac MiniSearchEngine.java && java MiniSearchEngine
 */
public class MiniSearchEngine {

    // ---------------- 1. the analyzer ----------------
    private static final Set<String> STOPWORDS = Set.of("a", "an", "the", "to", "in", "of", "and", "for");

    static List<String> analyze(String text) {
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .filter(t -> !t.isBlank() && !STOPWORDS.contains(t))
                .map(MiniSearchEngine::stem)
                .toList();
    }

    /** Toy stemmer: strips common suffixes ("flights"->"flight", "booking"->"book"). */
    static String stem(String t) {
        for (String suffix : List.of("ings", "ing", "es", "s", "ed"))
            if (t.length() > suffix.length() + 2 && t.endsWith(suffix))
                return t.substring(0, t.length() - suffix.length());
        return t;
    }

    // ---------------- 2. the inverted index ----------------
    private final Map<String, Map<Integer, Integer>> postings = new HashMap<>(); // term -> {docId -> tf}
    private final Map<Integer, String> docs = new HashMap<>();
    private final Map<Integer, Integer> docLength = new HashMap<>();
    private final Map<String, Set<String>> edgeNGrams = new HashMap<>();         // prefix -> full terms

    public void index(int docId, String text) {
        docs.put(docId, text);
        List<String> terms = analyze(text);
        docLength.put(docId, terms.size());
        for (String term : terms) {
            postings.computeIfAbsent(term, t -> new HashMap<>()).merge(docId, 1, Integer::sum);
            for (int i = 1; i <= term.length(); i++)                             // edge n-grams
                edgeNGrams.computeIfAbsent(term.substring(0, i), p -> new TreeSet<>()).add(term);
        }
    }

    // ---------------- 3+4. retrieval + TF-IDF ranking ----------------
    public List<Map.Entry<Integer, Double>> search(String query) {
        List<String> qTerms = analyze(query);
        if (qTerms.isEmpty()) return List.of();

        // Boolean AND: intersect posting lists (smallest first — a real optimization).
        List<Set<Integer>> lists = qTerms.stream()
                .map(t -> postings.getOrDefault(t, Map.of()).keySet())
                .sorted(Comparator.comparingInt(Set::size))
                .collect(Collectors.toList());
        Set<Integer> candidates = new HashSet<>(lists.get(0));
        for (Set<Integer> l : lists.subList(1, lists.size())) candidates.retainAll(l);

        // TF-IDF: score(d) = sum over query terms of tf(t,d)/|d| * log(N/df(t))
        int totalDocs = docs.size();
        Map<Integer, Double> scores = new HashMap<>();
        for (int d : candidates) {
            double score = 0;
            for (String t : qTerms) {
                Map<Integer, Integer> posting = postings.getOrDefault(t, Map.of());
                double tf = (double) posting.getOrDefault(d, 0) / docLength.get(d);
                double idf = Math.log((double) totalDocs / Math.max(1, posting.size()));
                score += tf * idf;
            }
            scores.put(d, score);
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .toList();
    }

    // ---------------- 5. autocomplete ----------------
    public Set<String> suggest(String prefix) {
        return edgeNGrams.getOrDefault(stem(prefix.toLowerCase()), Set.of());
    }

    // ---------------- demo ----------------
    public static void main(String[] args) {
        MiniSearchEngine engine = new MiniSearchEngine();
        engine.index(1, "Cheap flights to Goa");
        engine.index(2, "Hotel booking in Goa and Mumbai");
        engine.index(3, "Cheap flight deals for the summer");
        engine.index(4, "Mumbai to Goa train booking");
        engine.index(5, "Business class flights, cheap upgrades, flight tips");

        System.out.println("== index contents ==");
        engine.postings.forEach((term, posting) ->
                System.out.printf("  %-10s -> %s%n", term, posting));

        System.out.println("\n== search: \"cheap flights\" (stemming matches 'flight' too) ==");
        for (var hit : engine.search("cheap flights"))
            System.out.printf("  doc %d (score %.4f): %s%n",
                    hit.getKey(), hit.getValue(), engine.docs.get(hit.getKey()));
        System.out.println("  note: doc 5 mentions flight/cheap most often but is longer;"
                + "\n        TF normalization and IDF decide the order — that's ranking.");

        System.out.println("\n== search: \"goa booking\" (AND intersection) ==");
        for (var hit : engine.search("goa booking"))
            System.out.printf("  doc %d (score %.4f): %s%n",
                    hit.getKey(), hit.getValue(), engine.docs.get(hit.getKey()));

        System.out.println("\n== autocomplete via edge n-grams ==");
        System.out.println("  'fl'  -> " + engine.suggest("fl"));
        System.out.println("  'boo' -> " + engine.suggest("boo"));
        System.out.println("  'mum' -> " + engine.suggest("mum"));
    }
}
