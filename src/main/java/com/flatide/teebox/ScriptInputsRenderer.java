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
            .append("#script-inputs td{vertical-align:middle;}#script-inputs .form-row-inline{margin-top:12px;align-items:center;}</style>");
        html.append("<div class='card-header'><h2>Inputs")
            .append(version != null ? " (" + escape(version) + ")" : " (new version)").append("</h2></div>");
        html.append("<p class='dim'>Describes the Props accepted by this version. Required fields and defaults are guidance; execution does not enforce or fill them.</p>");
        if (!editable) {
            html.append(schema != null ? "<pre>" + escape(schema.prettyJson()) + "</pre>"
                    : "<p class='empty'>No input definition</p>");
        } else {
            html.append("<div id='input-fields' class='table-wrap'></div>")
                .append("<div class='form-row-inline'><button type='button' id='input-add' class='btn btn-sm'>Add input</button>")
                .append("<button type='button' id='input-clear' class='btn btn-sm'>Remove definition</button>");
            if (version != null) {
                html.append("<button type='submit' form='version-source-form' data-inputs-save name='version' value='")
                    .append(escape(version)).append("' formaction='/admin/scripts/inputs/")
                    .append(escape(script.scriptId)).append("'>Save inputs</button>");
            }
            html.append("</div><details id='input-json-details' style='margin-top:12px;'><summary>JSON Schema (advanced)</summary>")
                .append("<textarea id='input-schema-json' name='inputSchemaJson' form='version-source-form' rows='10' ")
                .append("maxlength='").append(ScriptInputSchema.MAX_LENGTH).append("' style='width:100%;font-family:monospace;' ")
                .append("aria-label='Input schema JSON' placeholder='Leave blank for no input definition'>")
                .append(schema != null ? escape(schema.prettyJson()) : "")
                .append("</textarea></details><p id='input-schema-error' role='alert' style='color:#dc2626;'></p>")
                .append("<p class='dim'>Save and Save as new version also save these inputs. Use JSON Schema for defaults, choices, arrays, or nested objects.</p>");
        }
        return html.append("</div>").toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
