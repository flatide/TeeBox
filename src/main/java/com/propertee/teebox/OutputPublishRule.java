package com.propertee.teebox;

public class OutputPublishRule {
    public String stream = "stdout";
    public String pattern;
    public int captureGroup = 1;
    public String publishKey;
    public boolean firstOnly = true;

    public OutputPublishRule copy() {
        OutputPublishRule copy = new OutputPublishRule();
        copy.stream = stream;
        copy.pattern = pattern;
        copy.captureGroup = captureGroup;
        copy.publishKey = publishKey;
        copy.firstOnly = firstOnly;
        return copy;
    }
}
