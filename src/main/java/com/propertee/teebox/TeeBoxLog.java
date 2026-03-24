package com.propertee.teebox;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Logging facade using Log4j2.
 * Component name is used as the logger name.
 */
public class TeeBoxLog {

    public static void info(String component, String message) {
        logger(component).info(message);
    }

    public static void warn(String component, String message) {
        logger(component).warn(message);
    }

    public static void warn(String component, String message, Throwable t) {
        logger(component).warn(message, t);
    }

    public static void error(String component, String message) {
        logger(component).error(message);
    }

    public static void error(String component, String message, Throwable t) {
        logger(component).error(message, t);
    }

    private static Logger logger(String component) {
        return LogManager.getLogger(component);
    }
}
