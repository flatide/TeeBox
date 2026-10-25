/* ProperTee code editor for the TeeBox admin UI.
 *
 * Ported from the ProperTee playground (propertee-js/docs/index.html). The syntax highlighter
 * (highlightSyntax/escapeHtml) and the builtin catalog (RESULT_NOTE/BUILTIN_DOCS) are copied VERBATIM
 * (last synced: spec v0.31.0 — number patterns, TEXT, ALIGN_LEFT/ALIGN_RIGHT/ALIGN_CENTER; v0.30.0 —
 * rounding digits, TRUNC, FORMAT, PRINTF). When the language gains builtins, re-diff against the playground and sync; playground-only
 * annotations ("in-memory FS", mocked SHELL/HTTP notes) are intentionally NOT carried over. Each
 * entry's output is what its sample prints on TeeBox: it differs from the playground's for ENV
 * (mocked there) and FAIL (a JS runtime message), and outputNote marks one that varies or depends on
 * the server.
 * TeeBox-only host builtins (STREAM_FILE/THUMBNAIL) are kept on separate, clearly-marked
 * lines/categories so the verbatim parts stay diffable.
 * Only the wiring is new: it progressively upgrades any <textarea data-pt-editor> into a highlighted
 * editor (transparent textarea over a <pre> syntax overlay + line gutter), works for multiple editors
 * on a page, and optionally attaches a builtin-function reference panel (data-pt-panel). A textarea
 * with data-pt-breakpoints gets the playground's clickable breakpoint gutter plus separate current
 * debug-line (yellow) and positioned-error (red) highlights. No external dependency.
 */
(function () {
    'use strict';

    // ---- verbatim from the playground ----
    function escapeHtml(str) {
        return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function highlightSyntax(code) {
        var builtins = 'PRINT|PRINTF|FORMAT|TEXT|ALIGN_LEFT|ALIGN_RIGHT|ALIGN_CENTER|SUM|MAX|MIN|ABS|FLOOR|CEIL|ROUND|TRUNC|LEN|TO_NUMBER|TO_STRING|TYPE_OF|SLEEP|PUSH|POP|CONCAT|SLICE|CHARS|SPLIT|WORDS|JOIN|SUBSTRING|UPPERCASE|LOWERCASE|TRIM|CONTAINS|STARTS_WITH|ENDS_WITH|FIND|FIND_FIRST|FIND_LAST|MATCHES|REGEX_FIND|REGEX_FIND_ALL|REPLACE|HAS_KEY|KEYS|VALUES|ENTRIES|MERGE|REMOVE_KEY|SORT|SORT_DESC|SORT_BY|SORT_BY_DESC|REVERSE|RANDOM|MILTIME|DATE|TIME|JSON_PARSE|JSON_FORMAT|ENV|FILE_EXISTS|FILE_INFO|READ_FILE|READ_LINES|FILE_LINES|READ_JSON_FILE|WRITE_JSON_FILE|WRITE_FILE|WRITE_LINES|APPEND_FILE|MKDIR|LIST_DIR|LIST_DIR_NAMES|DELETE_FILE|HTTP_GET|HTTP_POST|HTTP|SHELL|SHELL_CTX|FAIL|UNWRAP|OK|ERR|IS_RESULT';
        // TeeBox host builtins (registered by ScriptExecutor) — NOT part of the playground list above;
        // keep them on this separate line so the verbatim line stays diffable against the playground.
        builtins += '|STREAM_FILE|THUMBNAIL';
        var keywords = 'if|then|elseif|else|end|loop|in|do|infinite|limit|break|continue|return|debug|function|thread|multi|monitor|and|or|not';
        var pattern = new RegExp(
            '(\\/\\*[\\s\\S]*?\\*\\/)'                         // block comments
            + '|(\\/\\/[^\\n]*)'                               // line comments
            + '|("(?:[^"\\\\]|\\\\.)*")'                       // strings
            + '|(\\b(?:' + builtins + ')\\b(?=\\s*\\())'       // built-in functions
            + '|(\\b(?:' + keywords + ')\\b)'                  // keywords
            + '|(\\b(?:true|false|null)\\b)'                   // booleans / null
            + '|(\\b\\d+\\.\\d+\\b|\\b\\d+\\b)'               // numbers
            + '|(::)'                                          // global prefix
            + '|(==|!=|>=|<=|\\.\\.|>|<|[+\\-*\\/%=])'         // operators
            , 'g'
        );
        var result = '';
        var lastIndex = 0;
        var match;
        while ((match = pattern.exec(code)) !== null) {
            if (match.index > lastIndex) {
                result += escapeHtml(code.substring(lastIndex, match.index));
            }
            var text = escapeHtml(match[0]);
            if (match[1]) result += '<span class="syn-cmt">' + text + '</span>';
            else if (match[2]) result += '<span class="syn-cmt">' + text + '</span>';
            else if (match[3]) result += '<span class="syn-str">' + text + '</span>';
            else if (match[4]) result += '<span class="syn-fn">' + text + '</span>';
            else if (match[5]) result += '<span class="syn-kw">' + text + '</span>';
            else if (match[6]) result += '<span class="syn-bool">' + text + '</span>';
            else if (match[7]) result += '<span class="syn-num">' + text + '</span>';
            else if (match[8]) result += '<span class="syn-gl">' + text + '</span>';
            else if (match[9]) result += '<span class="syn-op">' + text + '</span>';
            else result += text;
            lastIndex = pattern.lastIndex;
        }
        if (lastIndex < code.length) {
            result += escapeHtml(code.substring(lastIndex));
        }
        return result + '\n';
    }

    var RESULT_NOTE = 'A Result object: .ok=true with .value as below. On failure .ok=false and .value is an error message (the script keeps running — check res.ok).';
    var BUILTIN_DOCS = [
        { cat: 'I/O', fns: [
            { name: 'PRINT', sig: 'PRINT(args...)', desc: 'Print values separated by spaces.', returns: 'The empty object {} (PRINT is used for its side effect).', fails: 'Does not fail.', sample: 'PRINT("x =", 1 + 2)', output: 'x = 3' },
            { name: 'PRINTF', sig: 'PRINTF(format, values...)', desc: 'Print one line made from format, each {} or {pattern} placeholder replaced by the next value: PRINT(FORMAT(format, values...)) (spec v0.30.0). A pattern draws the number as a spreadsheet does — see TEXT (spec v0.31.0).', returns: 'The empty object {} (PRINTF is used for its side effect).', fails: 'Runtime error if the format is not valid, the values and placeholders differ in number, or a pattern gets a value that is not a number.', sample: 'PRINTF("Total: {#,##0.00} won ({0.0%})", 1234.5, 0.456)', output: 'Total: 1,234.50 won (45.6%)' },
        ]},
        { cat: 'Math', fns: [
            { name: 'SUM', sig: 'SUM(args...)', desc: 'Sum of all numeric arguments.', returns: 'A number (0 with no arguments).', fails: 'Runtime error (stops the script) if any argument is not a number.', sample: 'PRINT(SUM(1, 2, 3))', output: '6' },
            { name: 'MAX', sig: 'MAX(args...)', desc: 'Maximum of all numeric arguments.', returns: 'A number.', fails: 'Runtime error if no arguments are given, or any argument is not a number.', sample: 'PRINT(MAX(4, 9, 2))', output: '9' },
            { name: 'MIN', sig: 'MIN(args...)', desc: 'Minimum of all numeric arguments.', returns: 'A number.', fails: 'Runtime error if no arguments are given, or any argument is not a number.', sample: 'PRINT(MIN(4, 9, 2))', output: '2' },
            { name: 'ABS', sig: 'ABS(n)', desc: 'Absolute value.', returns: 'A number.', fails: 'Runtime error if n is not a number.', sample: 'PRINT(ABS(-7))', output: '7' },
            { name: 'FLOOR', sig: 'FLOOR(n, [digits])', desc: 'Round down, toward -infinity. With digits, keep that many decimal places; a negative digits rounds to tens, hundreds, ... (spec v0.30.0). The digits PRINT shows are rounded, so FLOOR(0.29, 2) is 0.29.', returns: 'An integer without digits; a decimal with digits.', fails: 'Runtime error if n is not a number, digits is not an integer, or there is a third argument.', sample: 'PRINT(FLOOR(3.8), FLOOR(3.48, 1), FLOOR(-3.48, 1))', output: '3 3.4 -3.5' },
            { name: 'CEIL', sig: 'CEIL(n, [digits])', desc: 'Round up, toward +infinity. With digits, keep that many decimal places; a negative digits rounds to tens, hundreds, ... (spec v0.30.0).', returns: 'An integer without digits; a decimal with digits.', fails: 'Runtime error if n is not a number, digits is not an integer, or there is a third argument.', sample: 'PRINT(CEIL(3.2), CEIL(3.41, 1), CEIL(1201, -2))', output: '4 3.5 1300' },
            { name: 'ROUND', sig: 'ROUND(n, [digits])', desc: 'Round to the nearest; a tie goes away from zero, so ROUND(-2.5) is -3. With digits, keep that many decimal places (spec v0.30.0); the digits PRINT shows are rounded, so ROUND(2.675, 2) is 2.68.', returns: 'An integer without digits; a decimal with digits.', fails: 'Runtime error if n is not a number, digits is not an integer, or there is a third argument.', sample: 'PRINT(ROUND(3.5), ROUND(-2.5), ROUND(2.675, 2))', output: '4 -3 2.68' },
            { name: 'TRUNC', sig: 'TRUNC(n, [digits])', desc: 'Round toward zero, cutting the digits off. With digits, keep that many decimal places (spec v0.30.0).', returns: 'An integer without digits; a decimal with digits.', fails: 'Runtime error if n is not a number, digits is not an integer, or there is a third argument.', sample: 'PRINT(TRUNC(3.48, 1), TRUNC(-3.48, 1), TRUNC(-3.9))', output: '3.4 -3.4 -3' },
            { name: 'RANDOM', sig: 'RANDOM() | RANDOM(min, max)', desc: 'Random decimal [0,1), or random integer in an inclusive range. The single-argument form was removed in spec v0.7.0.', returns: 'A number: decimal in [0,1) with no args, else an integer.', fails: 'Runtime error if called with one argument, arguments are not numbers, a bound is not an integer within ±9,007,199,254,740,991, or min > max.', sample: 'PRINT(RANDOM(1, 6))', output: '4', outputNote: 'varies' },
        ]},
        { cat: 'Conversion', fns: [
            { name: 'TO_NUMBER', sig: 'TO_NUMBER(s)', desc: 'Convert a decimal numeral to a number (spec v0.25.0): an optional sign, digits with an optional fraction (.5 and 5. included), and an optional exponent (1e3, 2.5E-4). Surrounding whitespace — the six TRIM characters — is ignored. Hexadecimal, octal and binary, type suffixes, Infinity and NaN do not convert.', returns: 'A number — an integer for digits-only text within ±9,007,199,254,740,991, otherwise a decimal.', fails: 'Runtime error if s is not a string, is not a decimal numeral (empty text included), or is too large to represent (1e999).', sample: 'PRINT(TO_NUMBER(" 42\\n") + 1, TO_NUMBER("2.5e3"))', output: '43 2500' },
            { name: 'TO_STRING', sig: 'TO_STRING(v)', desc: 'Convert any value to its string form.', returns: 'A string.', fails: 'Does not fail (accepts any value).', sample: 'PRINT(TO_STRING(true))', output: 'true' },
            { name: 'TYPE_OF', sig: 'TYPE_OF(v)', desc: 'Type name of a value.', returns: 'A string: "number", "string", "boolean", "array", "object", or "null".', fails: 'Does not fail (accepts any value).', sample: 'PRINT(TYPE_OF([1, 2]))', output: 'array' },
        ]},
        { cat: 'String', fns: [
            { name: 'LEN', sig: 'LEN(s)', desc: 'Length of a string, array, or object.', returns: 'A number.', fails: 'Runtime error for other types (spec v0.7.0).', sample: 'PRINT(LEN("hello"))', output: '5' },
            { name: 'UPPERCASE', sig: 'UPPERCASE(s)', desc: 'Convert to uppercase.', returns: 'A string.', fails: 'Runtime error if s is not a string.', sample: 'PRINT(UPPERCASE("hi"))', output: 'HI' },
            { name: 'LOWERCASE', sig: 'LOWERCASE(s)', desc: 'Convert to lowercase.', returns: 'A string.', fails: 'Runtime error if s is not a string.', sample: 'PRINT(LOWERCASE("HI"))', output: 'hi' },
            { name: 'TRIM', sig: 'TRIM(s)', desc: 'Remove leading/trailing whitespace — exactly the six ASCII characters space, \\t, \\n, \\r, vertical tab and form feed (spec v0.24.0).', returns: 'A string.', fails: 'Runtime error if s is not a string.', sample: 'PRINT("[" + TRIM(" \\t hi \\n") + "]")', output: '[hi]' },
            { name: 'SUBSTRING', sig: 'SUBSTRING(s, start, [length])', desc: 'Up to length characters from position start (1-based); length omitted = the rest of s.', returns: 'A string; past the end there is just less (SUBSTRING("abc", 3, 5) is "c").', fails: 'Runtime error if s is not a string, start is not an integer of 1 or more, or length is not an integer of 0 or more (spec v0.34.0).', sample: 'PRINT(SUBSTRING("ProperTee", 1, 6))', output: 'Proper' },
            { name: 'SPLIT', sig: 'SPLIT(s, delimiter)', desc: 'Split s at every occurrence of the literal delimiter (not regex). Every field is kept — leading, interior and trailing empty strings — so JOIN(SPLIT(s, d), d) == s. For words, use WORDS.', returns: 'An array of strings.', fails: 'Runtime error if either argument is not a string, or the delimiter is empty (spec v0.24.0 — use CHARS for characters).', sample: 'PRINT(SPLIT("a,b,c", ","))\nPRINT(SPLIT(",a,,b,", ","))', output: '[ \'a\', \'b\', \'c\' ]\n[ \'\', \'a\', \'\', \'b\', \'\' ]' },
            { name: 'WORDS', sig: 'WORDS(s)', desc: 'Split s into its whitespace-separated words (spec v0.24.0): runs of whitespace separate words and leading/trailing whitespace is ignored. Whitespace is the same six characters as TRIM.', returns: 'An array of non-empty strings ([] for an empty or all-whitespace string).', fails: 'Runtime error if s is not a string.', sample: 'PRINT(WORDS("    test 1  2"))', output: '[ \'test\', \'1\', \'2\' ]' },
            { name: 'JOIN', sig: 'JOIN(arr, [separator])', desc: 'Join array elements into a string.', returns: 'A string (default separator is "").', fails: 'Runtime error if arr is not an array or separator is not a string.', sample: 'PRINT(JOIN(["a", "b"], "-"))', output: 'a-b' },
            { name: 'CHARS', sig: 'CHARS(s)', desc: 'Split a string into characters.', returns: 'An array of single-character strings.', fails: 'Runtime error if s is not a string.', sample: 'PRINT(CHARS("hi"))', output: '[ \'h\', \'i\' ]' },
            { name: 'CONTAINS', sig: 'CONTAINS(s, sub) / CONTAINS(arr, item)', desc: 'Substring test on a string, or array membership test (spec v0.15.0) — elements compared with == (by value, deep for objects/arrays, strict across types).', returns: 'A boolean.', fails: 'Runtime error if the first argument is not a string or array, or if it is a string and sub is not a string.', sample: 'PRINT(CONTAINS("hello", "ell"))\nPRINT(CONTAINS([1, 2, 3], 2))', output: 'true\ntrue' },
            { name: 'STARTS_WITH', sig: 'STARTS_WITH(s, prefix)', desc: 'Prefix test.', returns: 'A boolean.', fails: 'Runtime error if either argument is not a string.', sample: 'PRINT(STARTS_WITH("hello", "he"))', output: 'true' },
            { name: 'ENDS_WITH', sig: 'ENDS_WITH(s, suffix)', desc: 'Suffix test.', returns: 'A boolean.', fails: 'Runtime error if either argument is not a string.', sample: 'PRINT(ENDS_WITH("hello", "lo"))', output: 'true' },
            { name: 'FIND', sig: 'FIND(s, sub)', desc: 'All 1-based positions where sub occurs in s (spec v0.17.0). Literal match, not regex; ascending, overlapping occurrences included.', returns: 'An array of positions ([] if there is no occurrence).', fails: 'Runtime error if either argument is not a string, or sub is empty.', sample: 'PRINT(FIND("banana", "an"))\nPRINT(FIND("aaa", "aa"))', output: '[ 2, 4 ]\n[ 1, 2 ]' },
            { name: 'FIND_FIRST', sig: 'FIND_FIRST(s, sub)', desc: 'Position of the first occurrence of sub in s (spec v0.17.0). Literal match, not regex.', returns: 'A 1-based position, or 0 if absent — so "if FIND_FIRST(s, sub) > 0" tests presence.', fails: 'Runtime error if either argument is not a string, or sub is empty.', sample: 'PRINT(FIND_FIRST("abcd", "c"))', output: '3' },
            { name: 'FIND_LAST', sig: 'FIND_LAST(s, sub)', desc: 'Position of the last occurrence of sub in s (spec v0.17.0). Literal match, not regex.', returns: 'A 1-based position, or 0 if absent.', fails: 'Runtime error if either argument is not a string, or sub is empty.', sample: 'PRINT(FIND_LAST("banana", "an"))', output: '4' },
            { name: 'MATCHES', sig: 'MATCHES(s, pattern)', desc: 'Regex match test.', returns: 'A boolean (true if pattern matches anywhere in s).', fails: 'Runtime error if arguments are not strings or pattern is an invalid regex.', sample: 'PRINT(MATCHES("abc123", "[0-9]+"))', output: 'true' },
            { name: 'REGEX_FIND', sig: 'REGEX_FIND(s, pattern)', desc: 'Find the first regex match (spec v0.29.0).', returns: 'A match object {match, position, groups, named}: the whole match, its 1-based position, the groups in order (groups.N is group N) and the named groups by name; a group that took no part is {}. {} if there is no match.', fails: 'Runtime error if arguments are not strings or pattern is an invalid regex. NOTE: "no match" is not a failure — it returns {}.', sample: 'm = REGEX_FIND("id=42", "id=(?<n>[0-9]+)")\nPRINT(m.match, m.position, m.groups.1, m.named.n)', output: 'id=42 1 42 42' },
            { name: 'REGEX_FIND_ALL', sig: 'REGEX_FIND_ALL(s, pattern)', desc: 'Find every regex match, left to right and without overlap (spec v0.29.0).', returns: 'An array of match objects like REGEX_FIND\'s ([] if there is no match).', fails: 'Runtime error if arguments are not strings or pattern is an invalid regex.', sample: 'loop m in REGEX_FIND_ALL("id=42, id=7", "id=([0-9]+)") do\n    PRINT(m.position, m.groups.1)\nend', output: '1 42\n8 7' },
            { name: 'REPLACE', sig: 'REPLACE(s, target, replacement)', desc: 'Replace all literal occurrences of target.', returns: 'A string (literal match, not regex).', fails: 'Runtime error if any argument is not a string.', sample: 'PRINT(REPLACE("a.b.c", ".", "-"))', output: 'a-b-c' },
            { name: 'FORMAT', sig: 'FORMAT(format, values...)', desc: 'Build a string (spec v0.30.0): each {} takes the next value as PRINT shows it, and {pattern} writes a number as the pattern draws it (spec v0.31.0) — 0 is a digit always written, # a digit written only when there is one, . the decimal point, a comma groups the integer digits by three, a final % writes the number times 100. {{ and }} are literal braces.', returns: 'A string.', fails: 'Runtime error if the format is not valid, the values and placeholders differ in number, or a pattern gets a value that is not a number.', sample: 'PRINT(FORMAT("{} items, {#,##0} won, order {00000}", 3, 1234567, 42))', output: '3 items, 1,234,567 won, order 00042' },
            { name: 'TEXT', sig: 'TEXT(n, pattern)', desc: 'Write a number as a pattern draws it, as a spreadsheet\'s TEXT does (spec v0.31.0): 0 is a digit always written, # a digit written only when there is one, . the decimal point, a comma in the integer part groups the digits by three, a final % writes the number times 100. Rounding is ROUND\'s, so 2.675 with "0.00" is 2.68.', returns: 'A string.', fails: 'Runtime error if pattern is not a string or not a number pattern, n is not a number, or there is a third argument.', sample: 'PRINT(TEXT(1234567.891, "#,##0.00"), TEXT(7, "000"), TEXT(0.4567, "0.0%"), TEXT(3.5, "0.##"))', output: '1,234,567.89 007 45.7% 3.5' },
            { name: 'ALIGN_LEFT', sig: 'ALIGN_LEFT(value, width)', desc: 'Write the value as PRINT shows it and pad it with spaces on the right to width columns (spec v0.31.0). The width counts screen columns: a Hangul syllable, a Chinese character or most emoji take two. Longer text is not cut.', returns: 'A string.', fails: 'Runtime error if width is not an integer from 0 to 999, or there is a third argument.', sample: 'PRINT("[" + ALIGN_LEFT("ab", 5) + "]", "[" + ALIGN_LEFT("사과", 6) + "]")', output: '[ab   ] [사과  ]' },
            { name: 'ALIGN_RIGHT', sig: 'ALIGN_RIGHT(value, width)', desc: 'Like ALIGN_LEFT, padded on the left (spec v0.31.0), so the numbers of a column line up on the right.', returns: 'A string.', fails: 'Runtime error if width is not an integer from 0 to 999, or there is a third argument.', sample: 'loop row in [["apple", 1500], ["pear", 25000]] do\n    PRINT(ALIGN_LEFT(row.1, 6), ALIGN_RIGHT(TEXT(row.2, "#,##0"), 8))\nend', output: 'apple     1,500\npear     25,000' },
            { name: 'ALIGN_CENTER', sig: 'ALIGN_CENTER(value, width)', desc: 'Like ALIGN_LEFT, padded on both sides; an odd space goes on the right (spec v0.31.0).', returns: 'A string.', fails: 'Runtime error if width is not an integer from 0 to 999, or there is a third argument.', sample: 'PRINT("[" + ALIGN_CENTER("ab", 7) + "]")', output: '[  ab   ]' },
        ]},
        { cat: 'Array', fns: [
            { name: 'PUSH', sig: 'PUSH(arr, values...)', desc: 'Append values (original unchanged).', returns: 'A new array.', fails: 'Runtime error if the first argument is not an array.', sample: 'PRINT(PUSH([1, 2], 3))', output: '[ 1, 2, 3 ]' },
            { name: 'POP', sig: 'POP(arr)', desc: 'Remove the last element (original unchanged).', returns: 'A new array (empty stays empty).', fails: 'Runtime error if the argument is not an array.', sample: 'PRINT(POP([1, 2, 3]))', output: '[ 1, 2 ]' },
            { name: 'CONCAT', sig: 'CONCAT(arrs...)', desc: 'Concatenate arrays.', returns: 'A new array.', fails: 'Runtime error if any argument is not an array.', sample: 'PRINT(CONCAT([1], [2, 3]))', output: '[ 1, 2, 3 ]' },
            { name: 'SLICE', sig: 'SLICE(arr, start, [count])', desc: 'Sub-array of up to count elements from start (1-based); count omitted = rest of the array. Same start+count convention as SUBSTRING (spec v0.7.0).', returns: 'A new array; past the end there are just fewer elements.', fails: 'Runtime error if arr is not an array, start is not an integer of 1 or more, or count is not an integer of 0 or more (spec v0.34.0).', sample: 'PRINT(SLICE([1, 2, 3, 4], 2, 2))', output: '[ 2, 3 ]' },
            { name: 'SORT', sig: 'SORT(arr)', desc: 'Sort ascending.', returns: 'A new array.', fails: 'Runtime error if arr is not an array, or elements are mixed types (must be all numbers or all strings).', sample: 'PRINT(SORT([3, 1, 2]))', output: '[ 1, 2, 3 ]' },
            { name: 'SORT_DESC', sig: 'SORT_DESC(arr)', desc: 'Sort descending.', returns: 'A new array.', fails: 'Runtime error if arr is not an array, or elements are mixed types.', sample: 'PRINT(SORT_DESC([3, 1, 2]))', output: '[ 3, 2, 1 ]' },
            { name: 'SORT_BY', sig: 'SORT_BY(arr, key)', desc: 'Sort objects ascending by a key.', returns: 'A new array of objects.', fails: 'Runtime error if arr is not an array of objects, or the key values are not comparable.', sample: 'PRINT(SORT_BY([{"n": 2}, {"n": 1}], "n"))', output: '[ { "n": 1 }, { "n": 2 } ]' },
            { name: 'SORT_BY_DESC', sig: 'SORT_BY_DESC(arr, key)', desc: 'Sort objects descending by a key.', returns: 'A new array of objects.', fails: 'Runtime error if arr is not an array of objects, or the key values are not comparable.', sample: 'PRINT(SORT_BY_DESC([{"n": 1}, {"n": 2}], "n"))', output: '[ { "n": 2 }, { "n": 1 } ]' },
            { name: 'REVERSE', sig: 'REVERSE(arr)', desc: 'Reverse element order.', returns: 'A new array (any element types).', fails: 'Runtime error if the argument is not an array.', sample: 'PRINT(REVERSE([1, 2, 3]))', output: '[ 3, 2, 1 ]' },
        ]},
        { cat: 'Object', fns: [
            { name: 'HAS_KEY', sig: 'HAS_KEY(obj, key)', desc: 'Key-presence test.', returns: 'A boolean.', fails: 'Runtime error if obj is not an object or key is not a string.', sample: 'PRINT(HAS_KEY({"a": 1}, "a"))', output: 'true' },
            { name: 'KEYS', sig: 'KEYS(obj)', desc: 'Keys in insertion order.', returns: 'An array of strings.', fails: 'Runtime error if obj is not an object.', sample: 'PRINT(KEYS({"a": 1, "b": 2}))', output: '[ \'a\', \'b\' ]' },
            { name: 'VALUES', sig: 'VALUES(obj)', desc: 'Values in insertion order.', returns: 'An array.', fails: 'Runtime error if obj is not an object.', sample: 'PRINT(VALUES({"a": 1, "b": 2}))', output: '[ 1, 2 ]' },
            { name: 'ENTRIES', sig: 'ENTRIES(obj)', desc: 'Key/value pairs in insertion order.', returns: 'An array of {"key": k, "value": v} objects.', fails: 'Runtime error if obj is not an object.', sample: 'PRINT(ENTRIES({"a": 1}))', output: '[ { "key": \'a\', "value": 1 } ]' },
            { name: 'MERGE', sig: 'MERGE(obj1, obj2)', desc: 'Combine two objects.', returns: 'A new object (obj2 wins on key conflicts).', fails: 'Runtime error if either argument is not an object.', sample: 'PRINT(MERGE({"a": 1}, {"b": 2}))', output: '{ "a": 1, "b": 2 }' },
            { name: 'REMOVE_KEY', sig: 'REMOVE_KEY(obj, key)', desc: 'Drop a key.', returns: 'A new object (no error if the key is absent).', fails: 'Runtime error if obj is not an object or key is not a string.', sample: 'PRINT(REMOVE_KEY({"a": 1, "b": 2}, "a"))', output: '{ "b": 2 }' },
        ]},
        { cat: 'JSON', fns: [
            { name: 'JSON_PARSE', sig: 'JSON_PARSE(s)', desc: 'Parse a JSON string.', returns: RESULT_NOTE + ' .value = the parsed value (JSON null is preserved as null — spec v0.8.0).', fails: 'Returns a Result with .ok=false and .value = an error message if s is not a string or is invalid JSON. Does NOT stop the script.', sample: 'res = JSON_PARSE("{\\"a\\": 1}")\nPRINT(res.ok, res.value.a)', output: 'true 1' },
            { name: 'JSON_FORMAT', sig: 'JSON_FORMAT(v)', desc: 'Serialize any value to JSON.', returns: 'A JSON string.', fails: 'Runtime error if the value holds Infinity, -Infinity or NaN, which JSON cannot represent (spec v0.32.0); the message names the first one and where it is, e.g. value.rows.2.rate.', sample: 'PRINT(JSON_FORMAT({"a": [1, 2]}))', output: '{"a":[1,2]}' },
        ]},
        { cat: 'Results', fns: [
            { name: 'FAIL', sig: 'FAIL(message)', desc: 'Raise a runtime error at the call site — the explicit escalation for errors a script decides are fatal (spec v0.10.0).', returns: 'Never returns.', fails: 'Always — that is its purpose. The script stops exactly as with any runtime error; inside a multi thread, only that thread fails.', sample: 'res = JSON_PARSE("{bad")\nif not res.ok then\n    FAIL("config parse failed: " + res.value)\nend', output: 'Runtime Error at line 3:4: config parse failed: Invalid JSON: expected object key at position 1' },
            { name: 'UNWRAP', sig: 'UNWRAP(res, [message])', desc: 'The value of an ok genuine Result; an error Result escalates like FAIL with TO_STRING(res.value), prefixed "message: " when given.', returns: 'res.value when res.ok is true.', fails: 'Runtime error when res.ok is false, or when res is not a genuine Result (script literals are not — build them with OK/ERR).', sample: 'cfg = UNWRAP(JSON_PARSE("{\\"a\\": 1}"), "config")\nPRINT(cfg.a)', output: '1' },
            { name: 'OK', sig: 'OK([value])', desc: 'Construct a genuine Result {status: "done", ok: true, value}.', returns: 'A genuine Result. Missing value → {}.', fails: 'Does not fail.', sample: 'r = OK(42)\nPRINT(r.ok, UNWRAP(r))', output: 'true 42' },
            { name: 'ERR', sig: 'ERR([value])', desc: 'Construct a genuine error Result {status: "error", ok: false, value}. value may be any type (structured errors allowed).', returns: 'A genuine Result. Missing value → {}.', fails: 'Does not fail (UNWRAP on it does).', sample: 'r = ERR("boom")\nPRINT(r.ok, r.value)', output: 'false boom' },
            { name: 'IS_RESULT', sig: 'IS_RESULT(x)', desc: 'Observe the genuine-Result origin — true only for runtime- or OK/ERR-created Results (a hand-built {status, ok, value} literal is false).', returns: 'A boolean.', fails: 'Does not fail (accepts any value).', sample: 'PRINT(IS_RESULT(OK(1)))\nPRINT(IS_RESULT({"ok": true}))', output: 'true\nfalse' },
        ]},
        { cat: 'Time', fns: [
            { name: 'SLEEP', sig: 'SLEEP(ms)', desc: 'Pause the current thread for ms milliseconds.', returns: 'The empty object {}.', fails: 'Runtime error if ms is not a number.', sample: 'PRINT("before")\nSLEEP(300)\nPRINT("after")', output: 'before\nafter' },
            { name: 'MILTIME', sig: 'MILTIME()', desc: 'Current epoch time.', returns: 'An integer (epoch milliseconds).', fails: 'Does not fail.', sample: 'PRINT(MILTIME())', output: '1792592182371', outputNote: 'varies' },
            { name: 'DATE', sig: 'DATE()', desc: 'Current date.', returns: 'A string "YYYY-MM-DD".', fails: 'Does not fail.', sample: 'PRINT(DATE())', output: '2026-10-21', outputNote: 'varies' },
            { name: 'TIME', sig: 'TIME()', desc: 'Current time of day.', returns: 'A string "HH:MM:SS".', fails: 'Does not fail.', sample: 'PRINT(TIME())', output: '23:16:22', outputNote: 'varies' },
        ]},
        { cat: 'Environment', fns: [
            { name: 'ENV', sig: 'ENV(name, [default])', desc: 'Read an environment variable.', returns: 'The variable value (string); else default; else {} when unset and no default.', fails: 'A missing variable is NOT a failure (returns default or {}). Runtime error only if name is not a string.', sample: 'PRINT(ENV("USER"))\nPRINT(ENV("REGION", "us-east-1"))', output: 'teebox\nus-east-1', outputNote: 'example' },
        ]},
        { cat: 'File I/O', fns: [
            { name: 'FILE_EXISTS', sig: 'FILE_EXISTS(path)', desc: 'Existence test.', returns: 'A boolean (true if a file or directory exists at path).', fails: 'Returns a Result with .ok=false if path is not a string. Missing path is not a failure — it returns false.', sample: 'MKDIR("/d")\nPRINT(FILE_EXISTS("/d"))', output: 'true' },
            { name: 'FILE_INFO', sig: 'FILE_INFO(path)', desc: 'Metadata for a path.', returns: RESULT_NOTE + ' .value = {type: "file"|"dir", size, modified} — size in bytes and modified in epoch milliseconds, both integers.', fails: 'Returns .ok=false with an error message if path is not a string or does not exist.', sample: 'WRITE_FILE("/d/a.txt", "hi")\nres = FILE_INFO("/d/a.txt")\nPRINT(res.value.type, res.value.size)', output: 'file 2' },
            { name: 'READ_FILE', sig: 'READ_FILE(path)', desc: 'Read the whole file as one string (spec v0.18.0). The counterpart of WRITE_FILE; for large files use FILE_LINES (spec v0.26.0).', returns: RESULT_NOTE + ' .value = the file content (content-exact).', fails: 'Returns .ok=false if the file cannot be read.', sample: 'WRITE_FILE("/d/a.txt", "hi\\nthere")\nres = READ_FILE("/d/a.txt")\nPRINT(res.ok, LEN(res.value))', output: 'true 8' },
            { name: 'READ_LINES', sig: 'READ_LINES(path, [start], [count])', desc: 'Read lines (1-based start, count limit).', returns: RESULT_NOTE + ' .value = an array of line strings.', fails: 'Returns .ok=false if path is missing, or start/count are non-integers or < 1.', sample: 'WRITE_FILE("/d/a.txt", "x\\ny\\nz\\n")\nres = READ_LINES("/d/a.txt", 2, 1)\nPRINT(res.ok, res.value.1)', output: 'true y' },
            { name: 'FILE_LINES', sig: 'FILE_LINES(path)', desc: 'The file\'s lines as an ordinary array of strings (spec v0.26.0). The file is read as the array is used: a loop goes through it line by line keeping only the current line and LEN counts without keeping, so a file of any size can be processed in a loop; using the array as a whole (an element, SLICE, SORT, PRINT, ==, ...) reads it fully first and is refused above the host\'s whole-use limit (64 MB). The array is the file as it was at the call: lines appended later are not part of it, and any other change fails the next read. Lines end at \\n, \\r\\n or \\r; a leading UTF-8 BOM is dropped.', returns: 'An array of strings — not a Result.', fails: 'Runtime error at the call if the file does not exist or cannot be read; at the use if the file changed since the call, holds invalid UTF-8 or a line over 16 MB, or is too large to use as a whole.', sample: 'WRITE_LINES("/d/a.txt", ["name,age", "Alice,30", "Bob,25"])\nloop n, line in FILE_LINES("/d/a.txt") do\n    if n == 1 then continue end\n    PRINT(n, line)\nend', output: '2 Alice,30\n3 Bob,25' },
            { name: 'READ_JSON_FILE', sig: 'READ_JSON_FILE(path)', desc: 'Read a JSON file and parse it in one call (spec v0.18.0). Same conversion as JSON_PARSE; a leading UTF-8 BOM is ignored. Replaces the READ_LINES + JOIN + JSON_PARSE idiom.', returns: RESULT_NOTE + ' .value = the parsed value (object, array, or scalar; JSON null preserved).', fails: 'Returns .ok=false if the file cannot be read or its content is not valid JSON. Does NOT stop the script.', sample: 'WRITE_FILE("/d/cfg.json", "{\\"port\\": 8080}")\nres = READ_JSON_FILE("/d/cfg.json")\nPRINT(res.ok, res.value.port)', output: 'true 8080' },
            { name: 'WRITE_FILE', sig: 'WRITE_FILE(path, content)', desc: 'Write a string to a file (overwrite).', returns: RESULT_NOTE + ' .value = {} on success.', fails: 'Returns .ok=false with an error message if path is not a string, or the write fails (e.g. bad path / permission on a real host).', sample: 'res = WRITE_FILE("/d/a.txt", "hello")\nPRINT(res.ok)', output: 'true' },
            { name: 'WRITE_JSON_FILE', sig: 'WRITE_JSON_FILE(path, value)', desc: 'Serialize value as JSON (same output as JSON_FORMAT) and write it with a trailing newline, overwriting (spec v0.18.0). The counterpart of READ_JSON_FILE \u2014 the round-trip is lossless, null included.', returns: RESULT_NOTE + ' .value = {} on success.', fails: 'Returns .ok=false if path is not a string, or the write fails. Runtime error if value holds Infinity, -Infinity or NaN, and nothing is written (spec v0.32.0).', sample: 'WRITE_JSON_FILE("/d/cfg.json", {"port": 8080})\nPRINT(READ_JSON_FILE("/d/cfg.json").value.port)', output: '8080' },
            { name: 'WRITE_LINES', sig: 'WRITE_LINES(path, lines)', desc: 'Write an array of strings as lines.', returns: RESULT_NOTE + ' .value = {} on success.', fails: 'Returns .ok=false if path is not a string or lines is not an array, or the write fails.', sample: 'WRITE_LINES("/d/a.txt", ["one", "two"])\nres = READ_LINES("/d/a.txt")\nPRINT(LEN(res.value))', output: '2' },
            { name: 'APPEND_FILE', sig: 'APPEND_FILE(path, content)', desc: 'Append a string to a file.', returns: RESULT_NOTE + ' .value = {} on success.', fails: 'Returns .ok=false if path is not a string, or the append fails.', sample: 'WRITE_FILE("/d/a.txt", "a\\n")\nAPPEND_FILE("/d/a.txt", "b\\n")\nPRINT(LEN(READ_LINES("/d/a.txt").value))', output: '2' },
            { name: 'MKDIR', sig: 'MKDIR(path)', desc: 'Create a directory (including parents).', returns: RESULT_NOTE + ' .value = {} on success.', fails: 'Returns .ok=false if path is not a string, or creation fails on a real host.', sample: 'res = MKDIR("/a/b/c")\nPRINT(res.ok, FILE_EXISTS("/a/b/c"))', output: 'true true' },
            { name: 'LIST_DIR', sig: 'LIST_DIR(path, [pattern])', desc: 'List directory entries. A link is listed as the file or directory it points to; a link that cannot be followed (its target is missing or inaccessible, or links loop) is listed as type "link" with size 0 instead of failing the call (spec v0.27.0). pattern is a glob over entry names: * any run of characters, ? one character, [abc] [a-z] [!abc], {csv,txt}; \\ makes the next character literal.', returns: RESULT_NOTE + ' .value = an array of {name, type, size}, sorted by name; type is "file", "dir" or "link", and size is 0 for a directory or link.', fails: 'Returns .ok=false if path does not exist, is not a directory or cannot be read (the message names the reason, e.g. No such file or directory: /d), or if the pattern is empty, contains / or is malformed. A pattern that matches nothing gives an empty array.', sample: 'WRITE_FILE("/d/a.txt", "x")\nWRITE_FILE("/d/b.csv", "y")\nres = LIST_DIR("/d", "*.txt")\nPRINT(res.ok, res.value.1.name)', output: 'true a.txt' },
            { name: 'LIST_DIR_NAMES', sig: 'LIST_DIR_NAMES(path, [pattern])', desc: 'The name and own type of each entry, sorted (spec v0.28.0; the names alone in v0.27.0). No link is followed: every link is type "link", whatever it points to, so it is much faster than LIST_DIR on a large directory or one full of links. pattern works as in LIST_DIR.', returns: RESULT_NOTE + ' .value = an array of {name, type}, sorted by name; type is "file", "dir" or "link".', fails: 'Same as LIST_DIR.', sample: 'WRITE_FILE("/d/a.txt", "x")\nWRITE_FILE("/d/b.csv", "y")\nMKDIR("/d/sub")\nloop e in LIST_DIR_NAMES("/d").value do\n    PRINT(e.name, e.type)\nend\nPRINT(LIST_DIR_NAMES("/d", "*.csv").value)', output: 'a.txt file\nb.csv file\nsub dir\n[ { "name": \'b.csv\', "type": \'file\' } ]' },
            { name: 'DELETE_FILE', sig: 'DELETE_FILE(path)', desc: 'Delete a single file.', returns: RESULT_NOTE + ' .value = {} on success.', fails: 'Returns .ok=false if path is not a string, does not exist, or is a directory (directories are rejected).', sample: 'WRITE_FILE("/d/a.txt", "x")\nres = DELETE_FILE("/d/a.txt")\nPRINT(res.ok, FILE_EXISTS("/d/a.txt"))', output: 'true false' },
        ]},
        { cat: 'Shell', fns: [
            { name: 'SHELL', sig: 'SHELL(cmd) | SHELL(ctx, cmd)', desc: 'Run a shell command via the host TaskRunner.', returns: RESULT_NOTE + ' On exit code 0, .value = the command stdout.', fails: 'A non-zero exit returns .ok=false with the output in .value — e.g. cat on a missing file, false, or an unknown command ("... command not found").', sample: 'res = SHELL("echo hello")\nPRINT(res.ok, res.value)', output: 'true hello' },
            { name: 'SHELL_CTX', sig: 'SHELL_CTX(cwd, [env])', desc: 'Create a shell context (working dir + env) to pass to SHELL. Pass the Result directly — SHELL auto-unwraps it.', returns: RESULT_NOTE + ' .value = a context object {cwd, env}.', fails: 'Returns .ok=false if cwd is not a string or the directory does not exist.', sample: 'ctx = SHELL_CTX("/work")\nres = SHELL(ctx, "cat a.txt")\nPRINT(res.value)', output: 'hi', outputNote: 'example' },
        ]},
        { cat: 'HTTP', fns: [
            { name: 'HTTP_GET', sig: 'HTTP_GET(url, [options])', desc: 'HTTP GET. options = {headers, timeout}.', returns: RESULT_NOTE + ' .value = {status, body, headers}. .ok is true only for a 2xx status.', fails: 'A 4xx/5xx keeps the full .value with .ok=false. A transport failure (bad URL/DNS/connect/timeout) gives .ok=false with .value = {status:0, body:<message>, headers:{}}.', sample: 'res = HTTP_GET("http://api/data")\nPRINT(res.ok, res.value.status)\nPRINT(res.value.body)', output: 'true 200\n{"method":"GET","url":"http://api/data"}', outputNote: 'example' },
            { name: 'HTTP_POST', sig: 'HTTP_POST(url, body, [options])', desc: 'HTTP POST. A string body is sent as-is; an object/array body is serialized to JSON. options = {headers, timeout}.', returns: RESULT_NOTE + ' .value = {status, body, headers}, .ok = 2xx.', fails: 'Non-2xx -> .ok=false (value retained). Transport failure -> .ok=false, .value.status=0. Runtime error if a body that is not a string holds Infinity, -Infinity or NaN, and nothing is sent (spec v0.32.0).', sample: 'data = {"test": "ok"}\nopts = {"headers": {"Content-Type": "application/json"}}\nres = HTTP_POST("http://api/submit", data, opts)\nPRINT(res.ok, res.value.body)', output: 'true {"method":"POST","url":"http://api/submit","received":"{\\"test\\":\\"ok\\"}"}', outputNote: 'example' },
            { name: 'HTTP', sig: 'HTTP(method, url, [options])', desc: 'General request for any method (PUT/DELETE/PATCH/...). options = {headers, timeout, body}.', returns: RESULT_NOTE + ' .value = {status, body, headers}, .ok = 2xx.', fails: 'Same as HTTP_GET: non-2xx -> .ok=false; transport failure -> .value.status=0. Runtime error if options.body holds Infinity, -Infinity or NaN, and nothing is sent (spec v0.32.0).', sample: 'opts = {"headers": {"X-Token": "abc"}}\nres = HTTP("GET", "http://api/me", opts)\nPRINT(res.value.status)', output: '200', outputNote: 'example' },
        ]},
        // TeeBox-only host builtins (registered by ScriptExecutor; not part of the ProperTee language,
        // so this category does not exist in the playground catalog above).
        { cat: 'TeeBox Host', fns: [
            { name: 'STREAM_FILE', sig: 'STREAM_FILE(path, [contentType])', desc: 'Return a large file as the run result without loading it into memory: yields a small stream descriptor; clients fetch the bytes via GET .../runs/{id}/result-stream. Use as the script\'s return value.', returns: 'A raw stream descriptor object (not a Result) carrying {contentType, size}.', fails: 'Runtime error (stops the script) if the path is not an existing file inside the allowed stream roots (propertee.teebox.streamRoots, default = the TeeBox data dir).', sample: 'return STREAM_FILE("/data/report.pdf", "application/pdf")', output: '{"stream": true, "contentType": "application/pdf", "size": 2048}', outputNote: 'example' },
            { name: 'THUMBNAIL', sig: 'THUMBNAIL(srcPath, destPath, maxWidth, [maxHeight])', desc: 'Scale an image (anything ImageIO reads) to fit the bounds, preserving aspect ratio (never upscales), and write a PNG. Runs off the cooperative baton, so it never stalls concurrent multi workers.', returns: RESULT_NOTE + ' .value = {path, width, height}.', fails: 'Returns .ok=false for a missing/unreadable image, bad arguments, or a path outside the allowed stream roots (same policy as STREAM_FILE).', sample: 'res = THUMBNAIL("/data/in.png", "/data/thumb.png", 320)\nPRINT(res.ok, res.value.width)', output: 'true 320', outputNote: 'example' },
        ]},
    ];
    // outputNote: the shown output changes from run to run, or depends on the server and its files.
    var OUTPUT_NOTES = { varies: 'varies per run', example: 'example — depends on the environment' };
    var FN_INDEX = {};
    BUILTIN_DOCS.forEach(function (g) { g.fns.forEach(function (f) { FN_INDEX[f.name] = f; }); });

    // ---- editor wiring (generalized for multiple textareas) ----

    function lineNumbers(code) {
        var n = code.split('\n').length, s = '1';
        for (var i = 2; i <= n; i++) s += '\n' + i;
        return s;
    }

    function insertAtCursor(ta, text) {
        if (ta.readOnly) return;
        ta.focus();
        var start = ta.selectionStart, end = ta.selectionEnd, v = ta.value;
        ta.value = v.substring(0, start) + text + v.substring(end);
        ta.selectionStart = ta.selectionEnd = start + text.length;
        ta.dispatchEvent(new Event('input'));
    }

    // Tab indent / Shift-Tab dedent / Enter auto-indent / Ctrl-slash comment toggle.
    function handleKey(e, ta, refresh) {
        if (ta.readOnly) return;
        if (e.key === 'Tab') {
            e.preventDefault();
            var start = ta.selectionStart, end = ta.selectionEnd, value = ta.value;
            if (e.shiftKey) {
                var lineStart = value.lastIndexOf('\n', start - 1) + 1;
                var line = value.substring(lineStart, end);
                if (line.startsWith('    ')) {
                    ta.value = value.substring(0, lineStart) + line.substring(4) + value.substring(end);
                    ta.selectionStart = Math.max(lineStart, start - 4);
                    ta.selectionEnd = end - 4;
                }
            } else {
                ta.value = value.substring(0, start) + '    ' + value.substring(end);
                ta.selectionStart = ta.selectionEnd = start + 4;
            }
            refresh();
        } else if (e.key === 'Enter') {
            var s = ta.selectionStart, val = ta.value;
            var currentLine = val.substring(0, s).split('\n').pop();
            var indent = currentLine.match(/^\s*/)[0];
            var trimmed = currentLine.trim();
            var shouldIndent = ['then', 'do', 'else'].some(function (kw) { return trimmed.endsWith(kw); });
            if (shouldIndent || indent.length > 0) {
                e.preventDefault();
                var ins = '\n' + (shouldIndent ? indent + '    ' : indent);
                ta.value = val.substring(0, s) + ins + val.substring(s);
                ta.selectionStart = ta.selectionEnd = s + ins.length;
                refresh();
            }
        } else if (e.key === '/' && (e.ctrlKey || e.metaKey)) {
            e.preventDefault();
            var cs = ta.selectionStart, ce = ta.selectionEnd, cv = ta.value;
            var ls = cv.lastIndexOf('\n', cs - 1) + 1;
            var le = cv.indexOf('\n', ce); if (le === -1) le = cv.length;
            var ln = cv.substring(ls, le);
            if (ln.trim().startsWith('//')) {
                var un = ln.replace(/^(\s*)\/\/\s?/, '$1');
                ta.value = cv.substring(0, ls) + un + cv.substring(le);
                ta.selectionStart = cs; ta.selectionEnd = ce - (ln.length - un.length);
            } else {
                var ind = ln.match(/^\s*/)[0];
                var cm = ind + '// ' + ln.substring(ind.length);
                ta.value = cv.substring(0, ls) + cm + cv.substring(le);
                ta.selectionStart = cs + 3; ta.selectionEnd = ce + 3;
            }
            refresh();
        }
    }

    function upgrade(ta) {
        if (ta.ptUpgraded) return;
        ta.ptUpgraded = true;
        ta.classList.remove('pt-editor-fallback');
        ta.classList.add('pt-editor-input');
        ta.setAttribute('spellcheck', 'false');
        ta.setAttribute('wrap', 'off');

        var row = document.createElement('div');
        row.className = 'pt-editor-row';
        var breakpointMode = ta.hasAttribute('data-pt-breakpoints');
        var editor = document.createElement('div'); editor.className = 'pt-editor';
        if (breakpointMode) editor.classList.add('pt-editor-breakpoint-mode');
        var gutter = document.createElement('div'); gutter.className = 'pt-editor-lines'; gutter.textContent = '1';
        var bodyEl = document.createElement('div'); bodyEl.className = 'pt-editor-body';
        var markers = document.createElement('div'); markers.className = 'pt-editor-markers'; markers.setAttribute('aria-hidden', 'true');
        var debugMarker = document.createElement('div'); debugMarker.className = 'pt-editor-debug-line';
        var errorMarker = document.createElement('div'); errorMarker.className = 'pt-editor-error-line';
        var syntax = document.createElement('pre'); syntax.className = 'pt-editor-syntax'; syntax.setAttribute('aria-hidden', 'true');

        ta.parentNode.insertBefore(row, ta);
        row.appendChild(editor);
        editor.appendChild(gutter);
        editor.appendChild(bodyEl);
        bodyEl.appendChild(markers);
        markers.appendChild(debugMarker);
        markers.appendChild(errorMarker);
        bodyEl.appendChild(syntax);
        bodyEl.appendChild(ta);

        var breakpointLines = [];
        var debugLine = null;
        var errorLine = null;

        function normalizedLines(lines) {
            var seen = {}, result = [];
            for (var i = 0; i < (lines || []).length; i++) {
                var n = Number(lines[i]);
                if (n > 0 && n % 1 === 0 && !seen[n]) {
                    seen[n] = true;
                    result.push(n);
                }
            }
            result.sort(function (a, b) { return a - b; });
            return result;
        }
        function hasBreakpoint(line) {
            return breakpointLines.indexOf(line) !== -1;
        }
        function renderGutter() {
            if (!breakpointMode) {
                gutter.textContent = lineNumbers(ta.value);
                return;
            }
            var count = ta.value.split('\n').length;
            var fragment = document.createDocumentFragment();
            for (var i = 1; i <= count; i++) {
                var line = document.createElement('span');
                line.textContent = String(i);
                line.setAttribute('data-line', String(i));
                if (hasBreakpoint(i)) line.classList.add('pt-line-breakpoint');
                if (debugLine === i) line.classList.add('pt-line-debug');
                if (errorLine === i) line.classList.add('pt-line-error');
                fragment.appendChild(line);
            }
            gutter.textContent = '';
            gutter.appendChild(fragment);
        }
        function updateLineMarker(marker, line) {
            if (!line) {
                marker.style.display = 'none';
                return;
            }
            var style = window.getComputedStyle(ta);
            var lineHeight = parseFloat(style.lineHeight) || 22;
            var paddingTop = parseFloat(style.paddingTop) || 0;
            // The stylesheet default is display:none. Assigning '' merely removes the inline
            // declaration and exposes that default again, so the gutter changed while the code-row
            // marker stayed permanently hidden. Explicit block is the visible state.
            marker.style.display = 'block';
            marker.style.height = lineHeight + 'px';
            marker.style.top = (paddingTop + ((line - 1) * lineHeight) - ta.scrollTop) + 'px';
        }
        function updateLineMarkers() {
            updateLineMarker(debugMarker, debugLine);
            updateLineMarker(errorMarker, errorLine);
        }
        function syncScroll() {
            syntax.scrollTop = ta.scrollTop; syntax.scrollLeft = ta.scrollLeft;
            gutter.scrollTop = ta.scrollTop;
            updateLineMarkers();
        }
        function refresh() {
            syntax.innerHTML = highlightSyntax(ta.value);
            renderGutter();
            syncScroll();
        }
        function revealLine(line) {
            var style = window.getComputedStyle(ta);
            var lineHeight = parseFloat(style.lineHeight) || 22;
            var paddingTop = parseFloat(style.paddingTop) || 0;
            var top = paddingTop + ((line - 1) * lineHeight);
            var bottom = top + lineHeight;
            if (top < ta.scrollTop) {
                ta.scrollTop = Math.max(0, top - lineHeight);
            } else if (bottom > ta.scrollTop + ta.clientHeight) {
                ta.scrollTop = Math.max(0, bottom - ta.clientHeight + lineHeight);
            }
            syncScroll();
        }
        var api = {
            setBreakpoints: function (lines) {
                breakpointLines = normalizedLines(lines);
                renderGutter();
            },
            getBreakpoints: function () {
                return breakpointLines.slice();
            },
            setDebugLine: function (line, reveal) {
                var n = Number(line);
                debugLine = n > 0 && n % 1 === 0 ? n : null;
                renderGutter();
                updateLineMarkers();
                if (debugLine && reveal) revealLine(debugLine);
            },
            setErrorLine: function (line, reveal) {
                var n = Number(line);
                errorLine = n > 0 && n % 1 === 0 ? n : null;
                renderGutter();
                updateLineMarkers();
                if (errorLine && reveal) revealLine(errorLine);
            },
            setReadOnly: function (readOnly) {
                ta.readOnly = !!readOnly;
            },
            refresh: refresh
        };
        ta.ptEditor = api;
        ta.addEventListener('input', refresh);
        ta.addEventListener('scroll', syncScroll);
        // Debug consoles switch between editable PAUSED/ENDED and locked RUNNING states without
        // rebuilding the editor. Keep the key handler installed and let handleKey honor the live
        // textarea.readOnly property.
        ta.addEventListener('keydown', function (e) { handleKey(e, ta, refresh); });
        if (breakpointMode) {
            gutter.addEventListener('click', function (e) {
                var target = e.target;
                if (!target || !target.getAttribute) return;
                var line = parseInt(target.getAttribute('data-line'), 10);
                if (!(line > 0)) return;
                var index = breakpointLines.indexOf(line);
                if (index === -1) breakpointLines.push(line); else breakpointLines.splice(index, 1);
                breakpointLines.sort(function (a, b) { return a - b; });
                renderGutter();
                ta.dispatchEvent(new CustomEvent('pt-breakpoints-change', {
                    detail: { lines: breakpointLines.slice() }
                }));
            });
        }
        refresh();

        if (ta.hasAttribute('data-pt-panel')) {
            var resizer = document.createElement('div');
            resizer.className = 'pt-editor-resizer';
            resizer.title = 'Drag to resize';
            var panel = buildPanel(ta);
            row.appendChild(resizer);
            row.appendChild(panel);
            wireResizer(row, resizer, panel);
            syncPanelHeight(editor, panel);
            wirePanelToggle(editor, resizer, panel);
        }
        ta.dispatchEvent(new CustomEvent('pt-editor-ready', { detail: { editor: api } }));
    }

    // TeeBox addition (not from the playground): the reference panel is hidden by default — most
    // edits don't need it — and toggled by a small ƒ button pinned to the editor's top-right
    // corner. The resizer hides with the panel. The choice persists per browser via localStorage
    // (same pattern as the pages' auto-refresh toggle).
    function wirePanelToggle(editor, resizer, panel) {
        var KEY = 'teebox-fn-panel';
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'pt-fn-toggle';
        btn.textContent = 'ƒ';
        btn.title = 'Built-in function reference';
        editor.appendChild(btn);
        var open = false;
        try { open = window.localStorage.getItem(KEY) === '1'; } catch (e) { }
        function apply() {
            resizer.style.display = open ? '' : 'none';
            panel.style.display = open ? '' : 'none';
            btn.classList.toggle('active', open);
        }
        btn.addEventListener('click', function () {
            open = !open;
            try {
                if (open) { window.localStorage.setItem(KEY, '1'); } else { window.localStorage.removeItem(KEY); }
            } catch (e) { }
            apply();
        });
        apply();
    }

    // The panel stands exactly as tall as the code editor. The editor height is driven by the textarea
    // (its `rows` + user resize), so observe it and mirror the height onto the panel; the panel's list
    // scrolls internally. Falls back to a one-time sync where ResizeObserver is unavailable.
    function syncPanelHeight(editor, panel) {
        function apply() { panel.style.height = editor.offsetHeight + 'px'; }
        apply();
        if (typeof ResizeObserver === 'function') {
            new ResizeObserver(apply).observe(editor);
        }
    }

    // Drag the handle to trade width between the editor (flex:1) and the panel. Ported from the
    // playground's fn-resizer; scoped to this row so multiple editors on a page don't interfere.
    function wireResizer(row, resizer, panel) {
        var MIN = 220, EDITOR_MIN = 320, dragging = false;
        function onMove(clientX) {
            var rect = row.getBoundingClientRect();
            var width = rect.right - clientX;
            var max = rect.width - EDITOR_MIN;
            if (width < MIN) width = MIN;
            if (width > max) width = Math.max(MIN, max);
            panel.style.width = width + 'px';
        }
        resizer.addEventListener('mousedown', function (e) {
            e.preventDefault();
            dragging = true;
            resizer.classList.add('dragging');
            document.body.style.cursor = 'col-resize';
            document.body.style.userSelect = 'none';
        });
        document.addEventListener('mousemove', function (e) { if (dragging) onMove(e.clientX); });
        document.addEventListener('mouseup', function () {
            if (!dragging) return;
            dragging = false;
            resizer.classList.remove('dragging');
            document.body.style.cursor = '';
            document.body.style.userSelect = '';
        });
    }

    function buildPanel(ta) {
        var panel = document.createElement('div'); panel.className = 'pt-fn-panel';
        var header = document.createElement('div'); header.className = 'pt-fn-header';
        header.innerHTML = '<span class="pt-fn-title">Built-in Functions</span>';
        var list = document.createElement('div'); list.className = 'pt-fn-list';
        var detail = document.createElement('div'); detail.className = 'pt-fn-detail';
        detail.innerHTML = '<div class="pt-fn-empty">Select a function to see its signature, a sample and its output.</div>';

        var html = '';
        BUILTIN_DOCS.forEach(function (g) {
            html += '<div class="pt-fn-cat">' + escapeHtml(g.cat) + '</div>';
            g.fns.forEach(function (f) {
                html += '<button type="button" class="pt-fn-item" data-fn="' + escapeHtml(f.name) + '">' + escapeHtml(f.name) + '</button>';
            });
        });
        list.innerHTML = html;

        list.addEventListener('click', function (e) {
            var btn = e.target.closest ? e.target.closest('.pt-fn-item') : null;
            if (!btn) return;
            var f = FN_INDEX[btn.getAttribute('data-fn')];
            if (!f) return;
            Array.prototype.forEach.call(list.querySelectorAll('.pt-fn-item'), function (b) {
                b.classList.toggle('active', b === btn);
            });
            var returnsHtml = f.returns ? '<div class="pt-fn-field-label">Returns</div><div class="pt-fn-field">' + escapeHtml(f.returns) + '</div>' : '';
            var failsHtml = f.fails ? '<div class="pt-fn-field-label">Fails</div><div class="pt-fn-field pt-fn-field-fail">' + escapeHtml(f.fails) + '</div>' : '';
            // The trailing newline keeps a final empty output line visible.
            var outputHtml = f.output === undefined ? '' :
                '<div class="pt-fn-field-label">Output' +
                (f.outputNote ? '<span class="pt-fn-output-note"> · ' + escapeHtml(OUTPUT_NOTES[f.outputNote]) + '</span>' : '') + '</div>' +
                '<pre class="pt-fn-sample pt-fn-output">' + escapeHtml(f.output) + '\n</pre>';
            var insertHtml = ta.readOnly ? '' :
                '<button type="button" class="pt-fn-insert">Insert ' + escapeHtml(f.name) + '()</button>';
            detail.innerHTML =
                '<div class="pt-fn-sig">' + escapeHtml(f.sig) + '</div>' +
                '<div class="pt-fn-desc">' + escapeHtml(f.desc) + '</div>' +
                returnsHtml + failsHtml +
                '<div class="pt-fn-field-label">Sample</div><pre class="pt-fn-sample">' + escapeHtml(f.sample) + '</pre>' +
                outputHtml + insertHtml;
            var insert = detail.querySelector('.pt-fn-insert');
            if (insert) {
                insert.addEventListener('click', function () {
                    insertAtCursor(ta, f.name + '()');
                });
            }
        });

        panel.appendChild(header);
        panel.appendChild(list);
        panel.appendChild(detail);
        return panel;
    }

    function init() {
        var editors = document.querySelectorAll('textarea[data-pt-editor]');
        Array.prototype.forEach.call(editors, upgrade);
    }
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
