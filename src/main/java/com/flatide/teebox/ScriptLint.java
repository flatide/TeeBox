package com.flatide.teebox;

import com.flatide.propertee2.core.ScriptParser;
import com.flatide.propertee2.parser.ProperTeeParser;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Editor pre-check: syntax (the exact parser the save paths reject with) plus an unknown-builtin
 * lint. All-uppercase function names are reserved for builtins/host functions (spec v0.12.0 — a
 * script cannot define one), so an ALL-CAPS call that is not in the runtime's known-name set can
 * never resolve and is guaranteed to fail at call time: reporting it here has zero false positives.
 * Lowercase calls are NOT checked — they may be script functions defined elsewhere (including
 * later in the file). The whole tree is scanned, dead branches included (same stance as the
 * engine's load-time rejection of blocked constructs).
 */
final class ScriptLint {

    private static final Pattern ALL_CAPS = Pattern.compile("[A-Z][A-Z0-9_]*");

    private ScriptLint() {
    }

    /** Parse-check plus unknown-builtin lint. Returns error strings (empty = clean); saves nothing. */
    static List<String> check(String content, Set<String> knownFunctionNames) {
        List<String> errors = new ArrayList<String>();
        if (content == null || content.trim().length() == 0) {
            errors.add("content is required");
            return errors;
        }
        ProperTeeParser.RootContext tree = ScriptParser.parse(content, errors);
        if (tree == null) {
            return errors;   // syntax errors already collected by the parser
        }
        collectUnknownCalls(tree, knownFunctionNames, errors);
        return errors;
    }

    private static void collectUnknownCalls(ParseTree node, Set<String> known, List<String> errors) {
        if (node instanceof ProperTeeParser.FunctionCallContext) {
            Token name = ((ProperTeeParser.FunctionCallContext) node).funcName;
            String text = name.getText();
            if (ALL_CAPS.matcher(text).matches() && !known.contains(text)) {
                StringBuilder sb = new StringBuilder();
                sb.append("Line ").append(name.getLine()).append(":").append(name.getCharPositionInLine())
                  .append(" - unknown function '").append(text).append("'");
                String suggestion = nearest(text, known);
                if (suggestion != null) {
                    sb.append(" (did you mean '").append(suggestion).append("'?)");
                }
                errors.add(sb.toString());
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectUnknownCalls(node.getChild(i), known, errors);
        }
    }

    /** Closest known name within edit distance 2, ties broken by iteration order (sorted set). */
    private static String nearest(String name, Set<String> known) {
        String best = null;
        int bestDistance = 3;
        for (String candidate : known) {
            int d = editDistance(name, candidate, bestDistance);
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        return best;
    }

    /** Levenshtein distance, capped: returns limit when the distance is >= limit (early exit). */
    private static int editDistance(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) >= limit) {
            return limit;
        }
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            int rowMin = curr[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, curr[j]);
            }
            if (rowMin >= limit) {
                return limit;
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return Math.min(prev[b.length()], limit);
    }
}
