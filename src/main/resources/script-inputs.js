(function () {
    'use strict';
    var source = document.getElementById('input-schema-json');
    var fields = document.getElementById('input-fields');
    var error = document.getElementById('input-schema-error');
    var debugHint = document.getElementById('debug-input-hint');
    var types = ['', 'string', 'number', 'integer', 'boolean', 'object', 'array', 'null'];
    var schema = null;
    var rawOnly = false;
    // Invalid field drafts stay visible across row edits without replacing the last valid schema.
    // Key by the property definition so renaming an input does not lose its draft. A draft blocks
    // saving at once but is reported only when its field loses focus or a save/add is attempted,
    // so typing a quoted string is not flagged from its first keystroke.
    var defaultDrafts = new Map();

    function needsRawEditor(json) {
        // Keep high-precision defaults/enums out of JSON.parse -> stringify field edits.
        // String tokens are consumed whole so digits inside names/descriptions are ignored.
        var tokens = json.match(/"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g) || [];
        return tokens.some(function (token) {
            if (token.charAt(0) === '"') return false;
            var value = Number(token);
            var mantissa = token.split(/[eE]/)[0];
            var digits = mantissa.replace(/[-.]/g, '').replace(/^0+/, '').replace(/0+$/, '');
            return !Number.isFinite(value) || (value === 0 && /[1-9]/.test(mantissa))
                || digits.length > 15 || (Number.isInteger(value) && !Number.isSafeInteger(value));
        });
    }

    function object(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }
    function has(value, key) { return Object.prototype.hasOwnProperty.call(value, key); }
    function put(value, key, item) {
        // Property names are data, including __proto__ and constructor.
        Object.defineProperty(value, key, {value: item, enumerable: true, writable: true, configurable: true});
    }
    function message(text) {
        error.textContent = text;
    }
    function isDebug(button) { return /\/debug$/.test(button.getAttribute('formaction') || ''); }
    function reveal(draft) {
        draft.shown = true;
        if (draft.input) draft.input.setAttribute('aria-invalid', 'true');
    }
    // Report the first draft. A save/add attempt (focus) reports any draft; passive refreshes
    // skip a draft whose field is still being typed in.
    function showDefaultError(focus) {
        var props = schema && object(schema.properties) ? schema.properties : {};
        var names = Object.keys(props);
        for (var i = 0; i < names.length; i++) {
            var draft = defaultDrafts.get(props[names[i]]);
            if (!draft || !(focus || draft.shown)) continue;
            reveal(draft);
            message('Default for "' + names[i] + '": ' + draft.error);
            if (focus && draft.input) draft.input.focus();
            return true;
        }
        return false;
    }
    function describe(value) {
        if (value === null) return 'null';
        if (Array.isArray(value)) return 'an array';
        if (typeof value === 'number') return Number.isInteger(value) ? 'a number' : 'a non-integer number';
        return (typeof value === 'object' ? 'an ' : 'a ') + typeof value;
    }
    function matchesType(type, value) {
        if (type === 'integer') return typeof value === 'number' && Number.isInteger(value);
        if (type === 'null') return value === null;
        if (type === 'array') return Array.isArray(value);
        if (type === 'object') return object(value);
        return typeof value === type; // string, number, boolean
    }
    // A hint only: JSON Schema does not require a default to validate, so saving is never blocked.
    // Covers the usual slips of the quoted-JSON field: 123 for a string, "5" for a number.
    function typeMismatch(def) {
        if (!object(def) || !has(def, 'default') || defaultDrafts.has(def)) return '';
        var declared = def.type === undefined ? [] : [].concat(def.type);
        if (!declared.length || declared.some(function (type) { return types.indexOf(type) < 1; })) return '';
        var value = def.default;
        if (declared.some(function (type) { return matchesType(type, value); })) return '';
        var text = 'Default is ' + describe(value) + ', but the type is ' + declared.join(' | ') + '.';
        if (declared.indexOf('string') >= 0 && (typeof value === 'number' || typeof value === 'boolean')) {
            return text + ' Quote it to save text: ' + JSON.stringify(JSON.stringify(value)) + '.';
        }
        // Never suggest a bare value the browser would round.
        if (typeof value === 'string' && value.trim() && !needsRawEditor(value)) {
            var bare;
            try { bare = JSON.parse(value); } catch (e) { return text; }
            if ((bare === null || typeof bare === 'number' || typeof bare === 'boolean')
                    && declared.some(function (type) { return matchesType(type, bare); })) {
                return text + ' Remove the quotes to save ' + JSON.stringify(bare) + '.';
            }
        }
        return text;
    }
    function persist() {
        source.value = schema === null ? '' : JSON.stringify(schema, null, 2);
        if (source.value.length > source.maxLength) source.value = JSON.stringify(schema);
        if (!showDefaultError(false)) message('');
        showDebugHint();
    }
    function showDebugHint() {
        if (!debugHint) return;
        if (!schema) { debugHint.textContent = 'No input definition for this editor version.'; return; }
        var props = object(schema.properties) ? schema.properties : {};
        var required = Array.isArray(schema.required) ? schema.required : [];
        debugHint.textContent = 'Editor inputs: ' + (Object.keys(props).map(function (name) {
            var def = props[name];
            return name + (object(def) && def.type ? ' (' + [].concat(def.type).join(' | ') + ')' : '')
                + (required.indexOf(name) >= 0 ? ' — required' : '');
        }).join(', ') || 'See the JSON Schema above.');
    }
    function cell(row, node) { var td = document.createElement('td'); td.appendChild(node); row.appendChild(td); }
    function input(value, label) {
        var el = document.createElement('input'); el.type = 'text'; el.value = value;
        el.setAttribute('aria-label', label); el.style.width = '100%'; return el;
    }
    function render() {
        fields.textContent = '';
        document.getElementById('input-add').disabled = rawOnly;
        if (rawOnly) {
            var notice = document.createElement('p'); notice.className = 'dim';
            notice.textContent = 'This definition contains high-precision numbers. Use JSON Schema below to preserve their exact values.';
            fields.appendChild(notice); showDebugHint(); return;
        }
        var props = schema && object(schema.properties) ? schema.properties : {};
        var names = Object.keys(props);
        if (!names.length) {
            var empty = document.createElement('p'); empty.className = 'dim';
            empty.textContent = schema === null ? 'No input definition' : 'No named inputs. See JSON Schema for other constraints.';
            fields.appendChild(empty); showDebugHint(); return;
        }
        var table = document.createElement('table'); var head = document.createElement('thead');
        var header = document.createElement('tr');
        ['Name', 'Type', 'Required', 'Default (JSON)', 'Description', ''].forEach(function (label) {
            var th = document.createElement('th'); th.textContent = label; header.appendChild(th);
        });
        head.appendChild(header); table.appendChild(head);
        var body = document.createElement('tbody');
        names.forEach(function (originalName, index) {
            var name = originalName; var def = props[name];
            var row = document.createElement('tr');
            var nameInput = input(name, 'Input name');
            nameInput.addEventListener('change', function () {
                var next = nameInput.value;
                if (!next || (next !== name && has(props, next))) {
                    nameInput.value = name; message('Input names must be non-empty and unique.'); return;
                }
                if (next !== name) {
                    var rebuilt = {};
                    Object.keys(props).forEach(function (key) { put(rebuilt, key === name ? next : key, props[key]); });
                    schema.properties = props = rebuilt;
                    if (Array.isArray(schema.required)) schema.required = schema.required.map(function (key) { return key === name ? next : key; });
                    name = next;
                }
                persist();
            });
            cell(row, nameInput);
            var select = document.createElement('select'); select.setAttribute('aria-label', 'Input type');
            types.forEach(function (type) {
                var option = document.createElement('option'); option.value = type; option.textContent = type || 'any';
                select.appendChild(option);
            });
            var simple = object(def) && (def.type === undefined || typeof def.type === 'string');
            if (!simple) {
                var custom = document.createElement('option'); custom.value = 'custom'; custom.textContent = 'JSON Schema';
                select.appendChild(custom); select.value = 'custom'; select.disabled = true;
            } else { select.value = def.type || ''; }
            select.addEventListener('change', function () {
                if (select.value) def.type = select.value; else delete def.type;
                refreshWarning();
                persist();
            });
            cell(row, select);
            var required = document.createElement('input'); required.type = 'checkbox';
            required.setAttribute('aria-label', 'Required input');
            required.checked = Array.isArray(schema.required) && schema.required.indexOf(name) >= 0;
            required.addEventListener('change', function () {
                var list = Array.isArray(schema.required) ? schema.required.filter(function (key) { return key !== name; }) : [];
                if (required.checked) list.push(name);
                if (list.length) schema.required = list; else delete schema.required;
                persist();
            });
            cell(row, required);
            var draft = defaultDrafts.get(def);
            var defaultInput = input(draft ? draft.value
                : object(def) && has(def, 'default') ? JSON.stringify(def.default) : '', 'Input default (JSON)');
            defaultInput.className = 'input-default';
            defaultInput.placeholder = 'Not set';
            defaultInput.maxLength = source.maxLength;
            defaultInput.disabled = !object(def);
            var warning = document.createElement('div');
            warning.className = 'input-warning';
            warning.id = 'input-default-warning-' + index;
            defaultInput.setAttribute('aria-describedby', warning.id);
            if (draft) {
                draft.input = defaultInput;
                if (draft.shown) defaultInput.setAttribute('aria-invalid', 'true');
            }
            function refreshWarning() {
                var text = typeMismatch(def);
                warning.textContent = text;
                warning.hidden = !text;
                if (text) defaultInput.setAttribute('data-type-mismatch', 'true');
                else defaultInput.removeAttribute('data-type-mismatch');
            }
            refreshWarning();
            defaultInput.addEventListener('input', function () {
                if (!object(def)) return; // Boolean subschemas have no annotation fields.
                var text = defaultInput.value;
                var value;
                var problem = '';
                if (text.trim()) {
                    try { value = JSON.parse(text); }
                    catch (e) { problem = 'Enter valid JSON; quote text (e.g. "text"), or leave blank for no default.'; }
                    if (!problem && needsRawEditor(text)) {
                        problem = 'Use JSON Schema (advanced) for high-precision numbers to preserve their exact values.';
                    }
                }
                if (problem) {
                    // Blocks saving from now on. Once reported, the message follows further edits.
                    var previous = defaultDrafts.get(def);
                    var shown = !!(previous && previous.shown);
                    defaultDrafts.set(def, {value: text, error: problem, input: defaultInput, shown: shown});
                    refreshWarning();
                    if (shown) showDefaultError(false);
                    return;
                }
                defaultDrafts.delete(def);
                defaultInput.removeAttribute('aria-invalid');
                if (text.trim()) put(def, 'default', value); else delete def.default;
                refreshWarning();
                persist();
            });
            defaultInput.addEventListener('change', function () {
                var current = defaultDrafts.get(def);
                if (current && !current.shown) { reveal(current); showDefaultError(false); }
            });
            var defaultCell = document.createElement('div');
            defaultCell.appendChild(defaultInput); defaultCell.appendChild(warning);
            cell(row, defaultCell);
            var description = input(object(def) && typeof def.description === 'string' ? def.description : '', 'Input description');
            description.disabled = !object(def);
            description.addEventListener('input', function () {
                if (description.value) def.description = description.value; else delete def.description;
                persist();
            });
            cell(row, description);
            var remove = document.createElement('button'); remove.type = 'button'; remove.className = 'btn-danger btn-sm';
            remove.textContent = 'Remove';
            remove.addEventListener('click', function () {
                defaultDrafts.delete(def);
                delete props[name];
                if (Array.isArray(schema.required)) schema.required = schema.required.filter(function (key) { return key !== name; });
                persist(); render();
            });
            cell(row, remove); body.appendChild(row);
        });
        table.appendChild(body); fields.appendChild(table); showDebugHint(); showDefaultError(false);
    }
    function parse() {
        // An explicit advanced-JSON edit replaces field drafts. Other actions validate drafts
        // before calling parse, so Save/Add cannot silently discard an invalid default.
        defaultDrafts.clear();
        try {
            var next = source.value.trim() ? JSON.parse(source.value) : null;
            if (next !== null && !object(next)) throw new Error('Input schema must be a JSON object.');
            if (next && next.type !== undefined && next.type !== 'object') throw new Error('Root type must be object.');
            if (next && next.properties !== undefined && !object(next.properties)) throw new Error('Properties must be an object.');
            if (next && next.required !== undefined && (!Array.isArray(next.required) || next.required.some(function (x) { return typeof x !== 'string'; }))) {
                throw new Error('Required must be an array of input names.');
            }
            schema = next; rawOnly = needsRawEditor(source.value); message(''); render(); return true;
        } catch (e) {
            // Do not let stale field controls overwrite an unfinished/invalid JSON edit.
            fields.textContent = '';
            document.getElementById('input-add').disabled = true;
            if (debugHint) debugHint.textContent = 'Input definition contains invalid JSON or field shapes.';
            message(e.message); return false;
        }
    }
    if (source) {
        source.addEventListener('input', parse);
        document.getElementById('input-add').addEventListener('click', function () {
            if (showDefaultError(true)) return;
            if (!parse()) return;
            if (schema === null) schema = {type: 'object', properties: {}};
            if (!object(schema.properties)) schema.properties = {};
            var name = 'input'; var n = 1;
            while (has(schema.properties, name)) name = 'input' + n++;
            put(schema.properties, name, {type: 'string'}); persist(); render();
        });
        document.getElementById('input-clear').addEventListener('click', function () {
            schema = null; rawOnly = false; defaultDrafts.clear(); persist(); render();
        });
        source.addEventListener('invalid', function () { document.getElementById('input-json-details').open = true; });
        var form = document.getElementById('version-source-form');
        if (form) {
            form.addEventListener('keydown', function (event) {
                // Save inputs belongs to this form from outside it and precedes the form's own
                // buttons, so it is the default button for implicit submission: Enter in a text
                // field saved only the inputs and reloaded the page, discarding unsaved source
                // edits. Single-line fields never submit on Enter; Debug Props runs Debug, as Enter
                // did there before the Inputs card existed. IME commits keep their native Enter.
                if (event.key !== 'Enter' || event.isComposing || event.keyCode === 229) return;
                var target = event.target;
                if (!target || target.tagName !== 'INPUT'
                        || /^(button|submit|reset|image|file|color)$/i.test(target.type || '')) return;
                event.preventDefault();
                if (target.name !== 'propsJson' || event.repeat) return;
                var buttons = form.querySelectorAll('button');
                for (var i = 0; i < buttons.length; i++) {
                    if (isDebug(buttons[i]) && !buttons[i].disabled) { buttons[i].click(); return; }
                }
            });
            form.addEventListener('submit', function (event) {
                // Debug uses the editor's props as entered; the input definition is not saved by Debug.
                if (event.submitter && isDebug(event.submitter)) return;
                if (showDefaultError(true)) {
                    event.preventDefault(); event.stopImmediatePropagation(); return;
                }
                if (!parse()) {
                    event.preventDefault(); event.stopImmediatePropagation();
                    document.getElementById('input-json-details').open = true;
                }
            }, true);
        }
        parse();
    }

    var versionSelect = document.getElementById('run-input-version');
    var runHint = document.getElementById('run-input-hint');
    var generation = 0;
    if (versionSelect && runHint) {
        var refresh = function () {
            var current = ++generation;
            if (versionSelect.selectedIndex < 0 || versionSelect.options[versionSelect.selectedIndex].disabled) {
                runHint.textContent = 'Select a version to view its inputs.'; return;
            }
            runHint.textContent = 'Loading inputs…';
            var url = '/admin/scripts/' + encodeURIComponent(versionSelect.dataset.scriptId) + '/inputs';
            if (versionSelect.value) url += '?version=' + encodeURIComponent(versionSelect.value);
            fetch(url).then(function (response) {
                if (!response.ok) throw new Error('Unable to load input definition.');
                return response.text();
            }).then(function (json) {
                if (current !== generation) return;
                var result = JSON.parse(json);
                runHint.textContent = 'Version ' + result.version + '\n' + (result.hasInputSchema
                    ? (needsRawEditor(json) ? json : JSON.stringify(result.inputSchema, null, 2)) : 'No input definition');
            }).catch(function (err) { if (current === generation) runHint.textContent = err.message; });
        };
        versionSelect.addEventListener('change', refresh); refresh();
    }
})();
