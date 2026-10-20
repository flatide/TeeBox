'use strict';

// No browser or npm dependencies: execute the shipped editor against the small
// DOM surface it uses. Run with: node --test src/test/js/script-inputs.test.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const script = fs.readFileSync(path.resolve(__dirname, '../../main/resources/script-inputs.js'), 'utf8');

class Element {
    constructor(tagName) {
        this.tagName = tagName.toUpperCase();
        this.children = [];
        this.parentNode = null;
        this.attributes = {};
        this.listeners = {};
        this.style = {};
        this.dataset = {};
        this.value = '';
        this.disabled = false;
        this.checked = false;
        this.maxLength = 65536;
        this._textContent = '';
        this.validationMessage = '';
    }

    appendChild(child) {
        this.children.push(child);
        child.parentNode = this;
        return child;
    }

    removeChild(child) {
        this.children.splice(this.children.indexOf(child), 1);
        child.parentNode = null;
        return child;
    }

    set textContent(text) {
        for (const child of this.children) child.parentNode = null;
        this.children = [];
        this._textContent = String(text);
    }

    get textContent() {
        return this._textContent + this.children.map(child => child.textContent).join('');
    }

    setAttribute(name, value) {
        this.attributes[name] = String(value);
    }

    getAttribute(name) {
        return Object.hasOwn(this.attributes, name) ? this.attributes[name] : null;
    }

    removeAttribute(name) {
        delete this.attributes[name];
    }

    addEventListener(type, listener) {
        (this.listeners[type] ||= []).push(listener);
    }

    dispatch(type, properties = {}) {
        const event = {
            type,
            target: this,
            currentTarget: this,
            bubbles: false,
            defaultPrevented: false,
            stopped: false,
            propagationStopped: false,
            preventDefault() { this.defaultPrevented = true; },
            stopPropagation() { this.propagationStopped = true; },
            stopImmediatePropagation() { this.stopped = true; this.propagationStopped = true; },
            ...properties
        };
        // Bubbling events (keydown) also reach ancestor listeners, such as the form's.
        for (let element = this; element; element = event.bubbles ? element.parentNode : null) {
            event.currentTarget = element;
            for (const listener of element.listeners[type] || []) {
                listener.call(element, event);
                if (event.stopped) break;
            }
            if (event.propagationStopped) break;
        }
        return event;
    }

    click() {
        return this.dispatch('click');
    }

    setCustomValidity(message) {
        this.validationMessage = String(message);
    }

    checkValidity() {
        return !this.validationMessage;
    }

    reportValidity() {
        return this.checkValidity();
    }

    focus() {
        this.focused = true;
    }

    matches(selector) {
        if (selector.startsWith('#')) return this.id === selector.slice(1);
        const attribute = selector.match(/^\[([^=\]]+)(?:=["']([^"']*)["'])?\]$/);
        if (attribute) {
            return attribute[2] === undefined
                ? this.getAttribute(attribute[1]) !== null
                : this.getAttribute(attribute[1]) === attribute[2];
        }
        return this.tagName === selector.toUpperCase();
    }

    closest(selector) {
        let element = this;
        while (element) {
            if (element.matches(selector)) return element;
            element = element.parentNode;
        }
        return null;
    }

    querySelectorAll(selector) {
        return descendants(this).filter(element => element.matches(selector));
    }

    querySelector(selector) {
        return this.querySelectorAll(selector)[0] || null;
    }
}

function descendants(element) {
    return element.children.flatMap(child => [child, ...descendants(child)]);
}

function createEditor(properties, {raw, readOnly = false} = {}) {
    const elements = {};
    function add(id, tagName, parent) {
        const element = new Element(tagName);
        element.id = id;
        elements[id] = element;
        if (parent) parent.appendChild(element);
        return element;
    }

    function formControl(tagName, properties, parent) {
        const element = new Element(tagName);
        Object.assign(element, properties);
        parent.appendChild(element);
        return element;
    }

    const root = new Element('body');
    const form = readOnly ? null : add('version-source-form', 'form', root);
    const formFields = {};
    let debugButton = null;
    const source = readOnly ? null : add('input-schema-json', 'textarea', form);
    const fields = add('input-fields', 'div', form || root);
    const error = add('input-schema-error', 'p', form || root);
    add('debug-input-hint', 'p', form || root);
    if (!readOnly) {
        add('input-add', 'button', form);
        add('input-json-details', 'details', form);
        source.value = raw === undefined ? JSON.stringify({type: 'object', properties}) : raw;
        source.form = form;
        // The Version Source controls that keyboard handling distinguishes.
        formFields.content = formControl('textarea', {name: 'content'}, form);
        formFields.propsJson = formControl('input', {name: 'propsJson', type: 'text'}, form);
        formFields.captureGroup = formControl('input', {name: 'captureGroup', type: 'number'}, form);
        formFields.description = formControl('input', {name: 'description', type: 'text'}, form);
        formFields.activate = formControl('input', {name: 'activate', type: 'checkbox'}, form);
        debugButton = formControl('button', {type: 'submit'}, form);
        debugButton.setAttribute('formaction', '/admin/scripts/example/debug');
        debugButton.textContent = 'Debug';
    }
    const document = {
        getElementById(id) { return elements[id] || null; },
        createElement(tagName) { return new Element(tagName); },
        querySelector(selector) { return root.querySelector(selector); },
        querySelectorAll(selector) { return root.querySelectorAll(selector); }
    };
    vm.runInNewContext(script, {document, console}, {filename: 'script-inputs.js'});

    function row(name) {
        const nameInput = descendants(fields).find(element =>
            element.getAttribute('aria-label') === 'Input name' && element.value === name);
        assert.ok(nameInput, `Expected an input row named ${name}`);
        return nameInput.closest('tr');
    }

    function rowControl(name, label) {
        const result = descendants(row(name)).find(element => element.getAttribute('aria-label') === label);
        assert.ok(result, `Expected ${label} for ${name}`);
        return result;
    }

    function typeDefault(name, value) {
        const input = rowControl(name, 'Input default (JSON)');
        input.value = value;
        input.dispatch('input');
        return input;
    }

    function submit(action) {
        const submitter = new Element('button');
        if (action) submitter.setAttribute('formaction', action);
        return form.dispatch('submit', {submitter});
    }

    return {
        elements, root, form, source, fields, error, row, control: rowControl, submit, debugButton, typeDefault,
        schema() { return JSON.parse(source.value); },
        defaultInput(name) { return rowControl(name, 'Input default (JSON)'); },
        // Typing a value and then leaving the field, as editing one value usually goes.
        editDefault(name, value) {
            const input = typeDefault(name, value);
            input.dispatch('change');
            return input;
        },
        warning(name) {
            const id = rowControl(name, 'Input default (JSON)').getAttribute('aria-describedby');
            const result = descendants(fields).find(element => element.id === id);
            assert.ok(result, `Expected a type hint element for ${name}`);
            return result;
        },
        formField(name) { return formFields[name]; },
        pressEnter(element, properties = {}) {
            return element.dispatch('keydown', {
                key: 'Enter', keyCode: 13, isComposing: false, repeat: false, bubbles: true, ...properties
            });
        },
        editAdvanced(value) {
            source.value = typeof value === 'string' ? value : JSON.stringify(value);
            source.dispatch('input');
        },
        remove(name) {
            const button = descendants(row(name)).find(element =>
                element.tagName === 'BUTTON' && element.textContent === 'Remove');
            assert.ok(button, `Expected remove button for ${name}`);
            button.dispatch('click');
        }
    };
}

function assertInvalid(editor, input) {
    assert.equal(input.getAttribute('aria-invalid'), 'true');
    assert.ok(editor.error.textContent.trim(), 'An invalid default needs a visible error');
}

function assertValid(input) {
    assert.notEqual(input.getAttribute('aria-invalid'), 'true');
}

test('existing defaults render as JSON, including falsy and structured values', () => {
    const values = {
        zero: 0,
        disabled: false,
        nothing: null,
        empty: '',
        text: 'hello\n"world"',
        decimal: 1.25,
        list: [false, null, 'x', 2],
        record: {nested: {enabled: true}, values: [1, 2]}
    };
    const properties = Object.fromEntries(Object.entries(values).map(([name, value]) =>
        [name, {default: value}]));
    properties.unset = {type: 'string'};
    const editor = createEditor(properties);

    assert.ok(editor.fields.textContent.includes('Default (JSON)'));
    for (const [name, value] of Object.entries(values)) {
        const input = editor.defaultInput(name);
        assert.equal(input.value, JSON.stringify(value), name);
        assert.equal(input.placeholder, 'Not set');
        assert.equal(input.disabled, false);
    }
    assert.equal(editor.defaultInput('unset').value, '');
    assert.deepEqual(editor.schema().properties, properties);
});

test('valid default edits round-trip every JSON value without applying schema type validation', () => {
    const editor = createEditor({value: {type: 'integer', description: 'Kept'}});
    const values = ['a string', '', 0, false, null, 3.5, [1, 'two'], {key: [true, null]}];

    for (const value of values) {
        const input = editor.editDefault('value', JSON.stringify(value));
        assertValid(input);
        assert.deepEqual(editor.schema().properties.value, {
            type: 'integer',
            description: 'Kept',
            default: value
        });
        assert.equal(editor.error.textContent, '');
    }
    assert.equal(editor.submit().defaultPrevented, false);
});

test('blank default unsets the annotation while null and an empty JSON string remain explicit', () => {
    const editor = createEditor({value: {type: 'string', default: 'before'}});
    editor.editDefault('value', 'null');
    assert.equal(editor.schema().properties.value.default, null);
    editor.editDefault('value', '""');
    assert.equal(editor.schema().properties.value.default, '');
    editor.editDefault('value', ' \t ');
    assert.equal(Object.hasOwn(editor.schema().properties.value, 'default'), false);
    assert.equal(editor.schema().properties.value.type, 'string');
    assert.equal(editor.submit().defaultPrevented, false);
});

test('name, type, required, and description edits retain an existing default', () => {
    const value = {nested: ['retained', false]};
    const editor = createEditor({before: {type: 'object', default: value}});
    const name = editor.control('before', 'Input name');
    name.value = 'after';
    name.dispatch('change');
    const type = editor.control('after', 'Input type');
    type.value = 'array';
    type.dispatch('change');
    const required = editor.control('after', 'Required input');
    required.checked = true;
    required.dispatch('change');
    const description = editor.control('after', 'Input description');
    description.value = 'A description';
    description.dispatch('input');

    assert.deepEqual(editor.schema(), {
        type: 'object',
        properties: {after: {type: 'array', default: value, description: 'A description'}},
        required: ['after']
    });
    assert.equal(editor.defaultInput('after').value, JSON.stringify(value));
});

test('invalid default preserves the saved value and blocks all saves, but not Debug', () => {
    const editor = createEditor({value: {default: 4}});
    const original = editor.source.value;
    const input = editor.editDefault('value', '{"unfinished":');
    assertInvalid(editor, input);
    assert.equal(editor.source.value, original);

    for (const action of [undefined, '/admin/scripts/example/versions', '/admin/scripts/example/inputs']) {
        const event = editor.submit(action);
        assert.equal(event.defaultPrevented, true, `Save action ${action} must be blocked`);
        assert.equal(event.stopped, true);
        assert.equal(editor.defaultInput('value').value, '{"unfinished":');
        assertInvalid(editor, editor.defaultInput('value'));
    }
    assert.equal(editor.submit('/admin/scripts/example/debug').defaultPrevented, false);
    assert.equal(editor.schema().properties.value.default, 4);
});

test('a corrected or cleared default resolves the invalid state and permits saving', () => {
    const editor = createEditor({value: {default: false}});
    editor.editDefault('value', 'not-json');
    const corrected = editor.editDefault('value', 'true');
    assertValid(corrected);
    assert.equal(editor.error.textContent, '');
    assert.equal(editor.schema().properties.value.default, true);
    assert.equal(editor.submit().defaultPrevented, false);

    editor.editDefault('value', '{');
    const cleared = editor.editDefault('value', '');
    assertValid(cleared);
    assert.equal(editor.error.textContent, '');
    assert.equal(Object.hasOwn(editor.schema().properties.value, 'default'), false);
    assert.equal(editor.submit().defaultPrevented, false);
});

test('invalid drafts survive other field edits, rename, and removal of a different row', () => {
    const editor = createEditor({
        first: {type: 'string', default: 'original'},
        second: {type: 'number'}
    });
    editor.editDefault('first', '"unfinished');
    const description = editor.control('second', 'Input description');
    description.value = 'Changed independently';
    description.dispatch('input');
    assertInvalid(editor, editor.defaultInput('first'));
    assert.equal(editor.defaultInput('first').value, '"unfinished');

    const name = editor.control('first', 'Input name');
    name.value = 'renamed';
    name.dispatch('change');
    const type = editor.control('renamed', 'Input type');
    type.value = 'object';
    type.dispatch('change');
    editor.remove('second');

    assert.equal(editor.defaultInput('renamed').value, '"unfinished');
    assertInvalid(editor, editor.defaultInput('renamed'));
    assert.equal(editor.schema().properties.renamed.default, 'original');
    assert.equal(editor.schema().properties.renamed.type, 'object');
    assert.equal(editor.submit().defaultPrevented, true);
});

test('adding another input does not silently discard an invalid default draft', () => {
    const editor = createEditor({value: {default: 1}});
    editor.editDefault('value', '[unfinished');
    editor.elements['input-add'].dispatch('click');

    assert.equal(editor.defaultInput('value').value, '[unfinished');
    assertInvalid(editor, editor.defaultInput('value'));
    assert.equal(editor.submit().defaultPrevented, true);
});

test('removing an invalid row clears only that row error', () => {
    const editor = createEditor({first: {default: 1}, second: {default: 2}});
    editor.editDefault('first', '{');
    editor.editDefault('second', '[');
    editor.remove('first');
    assertInvalid(editor, editor.defaultInput('second'));
    assert.equal(editor.submit().defaultPrevented, true);

    editor.remove('second');
    assert.equal(editor.error.textContent, '');
    assert.equal(editor.source.value, '');
    assert.equal(editor.submit().defaultPrevented, false);
});

test('an explicit advanced JSON edit replaces pending default drafts and resynchronizes controls', () => {
    const editor = createEditor({value: {type: 'number', default: 1}});
    editor.editDefault('value', 'unfinished');
    editor.editAdvanced({
        type: 'object',
        properties: {value: {type: 'array', default: [false, null, 'new']}}
    });

    assert.equal(editor.defaultInput('value').value, '[false,null,"new"]');
    assertValid(editor.defaultInput('value'));
    assert.equal(editor.error.textContent, '');
    assert.equal(editor.submit().defaultPrevented, false);
});

test('removing the last parameter removes the definition and its pending invalid default', () => {
    const editor = createEditor({}, {raw: JSON.stringify({
        type: 'object',
        properties: {value: {type: 'string', default: 'x'}},
        required: ['value']
    })});
    editor.editDefault('value', '{"unfinished":');
    editor.remove('value');

    assert.equal(editor.source.value, '');
    assert.equal(editor.error.textContent, '');
    assert.equal(descendants(editor.fields).filter(element => element.tagName === 'INPUT').length, 0);
    assert.equal(editor.fields.textContent, 'No parameters');
    assert.equal(editor.submit().defaultPrevented, false);
});

test('adding and then removing a parameter leaves no definition behind', () => {
    const editor = createEditor({}, {raw: ''});
    assert.equal(editor.fields.textContent, 'No parameters');
    editor.elements['input-add'].dispatch('click');
    assert.deepEqual(editor.schema(), {type: 'object', properties: {input: {type: 'string'}}});
    editor.remove('input');
    assert.equal(editor.source.value, '');
    assert.equal(editor.fields.textContent, 'No parameters');
});

test('other schema keywords keep the definition when its last parameter is removed', () => {
    const editor = createEditor({}, {raw: JSON.stringify({
        type: 'object',
        properties: {first: {type: 'string'}, second: {type: 'number'}},
        required: ['first', 'second'],
        additionalProperties: false
    })});
    editor.remove('first');
    assert.deepEqual(editor.schema().required, ['second']);
    editor.remove('second');
    assert.deepEqual(editor.schema(), {type: 'object', properties: {}, additionalProperties: false});
    assert.match(editor.fields.textContent, /^No named parameters/);
});

test('an explicit empty definition from JSON Schema is kept as entered', () => {
    const editor = createEditor({});
    assert.match(editor.fields.textContent, /^No named parameters/);
    assert.equal(editor.submit().defaultPrevented, false);
    assert.deepEqual(editor.schema(), {type: 'object', properties: {}});
});

test('boolean subschemas cannot receive defaults while custom object schemas retain their constraints', () => {
    const properties = {
        anything: true,
        forbidden: false,
        custom: {type: ['string', 'null'], enum: ['a', null], default: null}
    };
    const editor = createEditor(properties);
    assert.equal(editor.defaultInput('anything').disabled, true);
    assert.equal(editor.defaultInput('forbidden').disabled, true);
    assert.equal(editor.defaultInput('custom').disabled, false);
    editor.editDefault('custom', '"a"');

    assert.deepEqual(editor.schema().properties, {
        anything: true,
        forbidden: false,
        custom: {type: ['string', 'null'], enum: ['a', null], default: 'a'}
    });
    assert.equal(editor.submit().defaultPrevented, false);
});

test('unsafe JSON numbers in field defaults are rejected without rounding saved values', () => {
    for (const raw of [
        '9007199254740993',
        '0.12345678901234567',
        '1e400',
        '1e-400',
        '{"nested":[9007199254740993]}'
    ]) {
        const editor = createEditor({value: {default: 7}});
        const before = editor.source.value;
        const input = editor.editDefault('value', raw);
        assertInvalid(editor, input);
        assert.match(editor.error.textContent, /JSON Schema/i);
        assert.equal(editor.source.value, before, raw);
        assert.equal(input.value, raw);
        assert.equal(editor.submit().defaultPrevented, true, raw);
    }
});

test('large numbers inside JSON strings are ordinary editable string values', () => {
    const editor = createEditor({value: {type: 'string'}});
    const input = editor.editDefault('value', '"9007199254740993 1e400"');
    assertValid(input);
    assert.equal(editor.schema().properties.value.default, '9007199254740993 1e400');
    assert.equal(editor.submit().defaultPrevented, false);
});

test('high-precision advanced JSON stays raw, with field controls disabled and exact source preserved', () => {
    const raw = '{"type":"object","properties":{"amount":{"type":"integer","default":9007199254740993}}}';
    const editor = createEditor({}, {raw});
    assert.equal(editor.elements['input-add'].disabled, true);
    assert.equal(descendants(editor.fields).filter(element => element.tagName === 'INPUT').length, 0);
    assert.match(editor.fields.textContent, /high-precision/i);
    assert.equal(editor.submit().defaultPrevented, false);
    assert.equal(editor.source.value, raw);
});

test('invalid advanced JSON removes stale controls until the schema is corrected', () => {
    const editor = createEditor({value: {default: false}});
    editor.editAdvanced('{"type":');
    assert.equal(descendants(editor.fields).filter(element => element.tagName === 'INPUT').length, 0);
    assert.equal(editor.elements['input-add'].disabled, true);
    assert.equal(editor.submit().defaultPrevented, true);
    editor.editAdvanced({type: 'object', properties: {value: {default: null}}});
    assert.equal(editor.defaultInput('value').value, 'null');
    assert.equal(editor.elements['input-add'].disabled, false);
    assert.equal(editor.submit().defaultPrevented, false);
});

test('read-only pages without a schema source do not create editing controls', () => {
    const editor = createEditor({}, {readOnly: true});
    assert.equal(editor.source, null);
    assert.equal(editor.form, null);
    assert.equal(descendants(editor.fields).length, 0);
});

test('an unfinished default stays quiet while typing, but blocks saving and reports on change', () => {
    const editor = createEditor({value: {type: 'string', default: 'before'}});
    const original = editor.source.value;
    const input = editor.typeDefault('value', '"unfin');
    assert.equal(input.getAttribute('aria-invalid'), null);
    assert.equal(editor.error.textContent, '');
    assert.equal(editor.source.value, original);

    input.dispatch('change');
    assertInvalid(editor, input);
    assert.match(editor.error.textContent, /Default for "value"/);
    // Once reported, the message follows further edits without waiting for another change.
    editor.typeDefault('value', '9007199254740993');
    assert.match(editor.error.textContent, /JSON Schema/);
    const fixed = editor.typeDefault('value', '"fixed"');
    assertValid(fixed);
    assert.equal(editor.error.textContent, '');
    assert.equal(editor.schema().properties.value.default, 'fixed');
});

test('a save or add attempt reports an unreported draft and focuses it', () => {
    const saving = createEditor({value: {default: 1}});
    const typed = saving.typeDefault('value', '[1,');
    assert.equal(saving.error.textContent, '');
    const event = saving.submit();
    assert.equal(event.defaultPrevented, true);
    assertInvalid(saving, typed);
    assert.equal(typed.focused, true);
    assert.equal(saving.schema().properties.value.default, 1);

    const adding = createEditor({value: {default: 1}});
    const pending = adding.typeDefault('value', '{"a":');
    adding.elements['input-add'].dispatch('click');
    assertInvalid(adding, pending);
    assert.equal(pending.focused, true);
    assert.deepEqual(Object.keys(adding.schema().properties), ['value']);
});

test('Enter in Version Source fields never submits; in Debug Props it runs Debug', () => {
    const editor = createEditor({value: {type: 'string'}});
    let debugClicks = 0;
    editor.debugButton.addEventListener('click', () => { debugClicks++; });

    for (const name of ['description', 'captureGroup', 'activate']) {
        assert.equal(editor.pressEnter(editor.formField(name)).defaultPrevented, true, name);
    }
    assert.equal(debugClicks, 0);
    assert.equal(editor.pressEnter(editor.formField('propsJson')).defaultPrevented, true);
    assert.equal(debugClicks, 1);

    // A held key does not start more Debug sessions; IME commits keep their native Enter.
    assert.equal(editor.pressEnter(editor.formField('propsJson'), {repeat: true}).defaultPrevented, true);
    assert.equal(editor.pressEnter(editor.formField('propsJson'), {isComposing: true}).defaultPrevented, false);
    assert.equal(editor.pressEnter(editor.formField('propsJson'), {keyCode: 229}).defaultPrevented, false);
    // Multi-line editing and buttons are untouched, as are other keys.
    assert.equal(editor.pressEnter(editor.formField('content')).defaultPrevented, false);
    assert.equal(editor.pressEnter(editor.debugButton).defaultPrevented, false);
    assert.equal(editor.pressEnter(editor.formField('description'), {key: 'a'}).defaultPrevented, false);
    assert.equal(debugClicks, 1);
});

test('a default that does not match its declared type is saved with a non-blocking hint', () => {
    const editor = createEditor({
        text: {type: 'string'},
        count: {type: 'integer'},
        flag: {type: 'boolean'},
        either: {type: ['string', 'null']},
        any: {}
    });
    const cases = [
        ['text', '123', /^Default is a number, but the type is string\. Quote it to save text: "123"\.$/],
        ['text', 'true', /Quote it to save text: "true"\.$/],
        ['text', '"123"', null],
        ['count', '1.5', /^Default is a non-integer number, but the type is integer\.$/],
        ['count', '2', null],
        ['count', '"5"', /^Default is a string, but the type is integer\. Remove the quotes to save 5\.$/],
        ['flag', '"true"', /Remove the quotes to save true\.$/],
        ['flag', '{"on":true}', /^Default is an object, but the type is boolean\.$/],
        ['flag', 'false', null],
        ['either', 'null', null],
        ['either', '5', /type is string \| null\. Quote it to save text: "5"\.$/],
        ['any', '[1]', null]
    ];
    for (const [name, raw, expected] of cases) {
        const input = editor.editDefault(name, raw);
        const label = `${name}=${raw}`;
        assertValid(input);
        assert.deepEqual(editor.schema().properties[name].default, JSON.parse(raw), label);
        const warning = editor.warning(name);
        if (expected) {
            assert.match(warning.textContent, expected, label);
            assert.equal(warning.hidden, false, label);
            assert.equal(input.getAttribute('data-type-mismatch'), 'true', label);
        } else {
            assert.equal(warning.textContent, '', label);
            assert.equal(warning.hidden, true, label);
            assert.equal(input.getAttribute('data-type-mismatch'), null, label);
        }
        assert.equal(editor.error.textContent, '', label);
        assert.equal(editor.submit().defaultPrevented, false, label);
    }
});

test('type hints follow type changes and give way to an invalid draft', () => {
    const editor = createEditor({value: {type: 'string', default: 5}});
    assert.match(editor.warning('value').textContent, /a number, but the type is string/);
    const type = editor.control('value', 'Input type');
    type.value = 'number';
    type.dispatch('change');
    assert.equal(editor.warning('value').textContent, '');
    type.value = 'boolean';
    type.dispatch('change');
    assert.match(editor.warning('value').textContent, /type is boolean\.$/);

    editor.typeDefault('value', '"unfinished');
    assert.equal(editor.warning('value').textContent, '');
    assert.equal(editor.warning('value').hidden, true);
    editor.editDefault('value', '');
    assert.equal(editor.warning('value').textContent, '');
    assert.equal(Object.hasOwn(editor.schema().properties.value, 'default'), false);
});

test('an unquote hint is never offered for a number the browser would round', () => {
    const editor = createEditor({value: {type: 'integer'}});
    editor.editDefault('value', '"9007199254740993"');
    assert.equal(editor.warning('value').textContent, 'Default is a string, but the type is integer.');
    assert.equal(editor.schema().properties.value.default, '9007199254740993');
});
