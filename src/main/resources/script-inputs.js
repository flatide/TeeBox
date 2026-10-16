(function () {
    'use strict';
    var source = document.getElementById('input-schema-json');
    var fields = document.getElementById('input-fields');
    var error = document.getElementById('input-schema-error');
    var debugHint = document.getElementById('debug-input-hint');
    var types = ['', 'string', 'number', 'integer', 'boolean', 'object', 'array', 'null'];
    var schema = null;
    var rawOnly = false;

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
    function persist() {
        source.value = schema === null ? '' : JSON.stringify(schema, null, 2);
        if (source.value.length > source.maxLength) source.value = JSON.stringify(schema);
        message('');
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
        ['Name', 'Type', 'Required', 'Description', ''].forEach(function (label) {
            var th = document.createElement('th'); th.textContent = label; header.appendChild(th);
        });
        head.appendChild(header); table.appendChild(head);
        var body = document.createElement('tbody');
        names.forEach(function (originalName) {
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
                delete props[name];
                if (Array.isArray(schema.required)) schema.required = schema.required.filter(function (key) { return key !== name; });
                persist(); render();
            });
            cell(row, remove); body.appendChild(row);
        });
        table.appendChild(body); fields.appendChild(table); showDebugHint();
    }
    function parse() {
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
            if (!parse()) return;
            if (schema === null) schema = {type: 'object', properties: {}};
            if (!object(schema.properties)) schema.properties = {};
            var name = 'input'; var n = 1;
            while (has(schema.properties, name)) name = 'input' + n++;
            put(schema.properties, name, {type: 'string'}); persist(); render();
        });
        document.getElementById('input-clear').addEventListener('click', function () { schema = null; rawOnly = false; persist(); render(); });
        source.addEventListener('invalid', function () { document.getElementById('input-json-details').open = true; });
        var form = document.getElementById('version-source-form');
        if (form) form.addEventListener('submit', function (event) {
            // Debug uses the editor's props as entered; the input definition is not saved by Debug.
            if (event.submitter && /\/debug$/.test(event.submitter.getAttribute('formaction') || '')) return;
            if (!parse()) {
                event.preventDefault(); event.stopImmediatePropagation();
                document.getElementById('input-json-details').open = true;
            }
        }, true);
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
