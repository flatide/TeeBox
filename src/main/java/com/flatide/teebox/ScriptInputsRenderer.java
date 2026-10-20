package com.flatide.teebox;

/** The version-specific inputs editor. The textarea is submitted with the source editor's form. */
final class ScriptInputsRenderer {
    private ScriptInputsRenderer() {}

    static String editor(ScriptInfo script, String version, boolean editable) {
        ScriptInputSchema schema = null;
        for (ScriptVersionInfo vi : script.versions) {
            if (vi.version.equals(version)) schema = vi.inputSchema;
        }
        StringBuilder html = new StringBuilder("<div class='card' id='script-inputs'>");
        html.append("<style>#script-inputs input[type=text],#script-inputs select,#input-schema-json{padding:8px 10px;border:1px solid #cbd5e1;border-radius:6px;font-size:14px;box-sizing:border-box;}")
            .append("#script-inputs td{vertical-align:middle;}#script-inputs table{min-width:820px;}")
            .append("#script-inputs .input-default{min-width:160px;font-family:monospace;}")
            .append("#script-inputs input[data-type-mismatch]{border-color:#d97706;}")
            .append("#script-inputs input[aria-invalid=true]{border-color:#dc2626;}")
            .append("#script-inputs .input-warning{margin-top:4px;font-size:12px;color:#b45309;}")
            .append("#input-fields p.dim{font-size:13px;}")
            .append("#script-inputs .form-row-inline{margin-top:12px;align-items:center;}")
            // Outlined like Check syntax: adding a row is a local edit, not a save. One pixel less
            // padding than a filled button makes up for the border, so the two buttons match.
            .append("#input-add{padding:7px 15px;background:#fff;color:#2563eb;border:1px solid #2563eb;}")
            .append("#input-add:hover{background:#eff6ff;}")
            .append("#input-add:disabled{background:#fff;color:#94a3b8;border-color:#cbd5e1;cursor:not-allowed;}</style>");
        html.append("<div class='card-header'><h2>Inputs")
            .append(version != null ? " (" + escape(version) + ")" : " (new version)").append("</h2></div>");
        if (!editable) {
            html.append(schema != null ? "<pre>" + escape(schema.prettyJson()) + "</pre>"
                    : "<p class='empty'>No input definition</p>");
        } else {
            html.append("<div id='input-fields' class='table-wrap'></div>")
                .append("<div class='form-row-inline'><button type='button' id='input-add'>Add parameter</button>");
            if (version != null) {
                html.append("<button type='submit' form='version-source-form' data-inputs-save style='margin-left:auto;' name='version' value='")
                    .append(escape(version)).append("' formaction='/admin/scripts/inputs/")
                    .append(escape(script.scriptId)).append("' title='Save these inputs to version ").append(escape(version))
                    .append(" without changing its source. Save and Save as new version also save them.'>Save inputs</button>");
            }
            html.append("</div><details id='input-json-details' style='margin-top:12px;'>")
                .append("<summary style='cursor:pointer;font-size:12px;color:#64748b;'>JSON Schema (advanced)</summary>")
                .append("<textarea id='input-schema-json' name='inputSchemaJson' form='version-source-form' rows='10' ")
                .append("maxlength='").append(ScriptInputSchema.MAX_LENGTH).append("' style='width:100%;font-family:monospace;margin-top:8px;' ")
                .append("aria-label='Input schema JSON' placeholder='Leave blank for no input definition'>")
                .append(schema != null ? escape(schema.prettyJson()) : "")
                .append("</textarea></details><p id='input-schema-error' role='alert' style='color:#dc2626;font-size:13px;margin-top:8px;'></p>")
                .append("<p class='dim' style='font-size:12px;margin-top:8px;'>For reference only: runs do not check Required or fill in defaults.</p>");
        }
        return html.append("</div>").toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
