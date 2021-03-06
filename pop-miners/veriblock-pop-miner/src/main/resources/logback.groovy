import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.filter.ThresholdFilter
import ch.qos.logback.contrib.jackson.JacksonJsonFormatter
import ch.qos.logback.contrib.json.classic.JsonLayout
import org.veriblock.core.utilities.LogLevelColorsConverter
import org.veriblock.shell.LoggingLineAppender

def logRootPath = System.getenv('VPM_LOG_PATH') ?: 'logs/'
def logLevel = System.getenv('VPM_LOG_LEVEL') ?: ''
def consoleLogLevel = System.getenv('VPM_CONSOLE_LOG_LEVEL') ?: ''
boolean addJsonLogs = System.getenv('VPM_ENABLE_JSON_LOG')?.toBoolean() ?: false

statusListener(NopStatusListener)

appender("TERMINAL", LoggingLineAppender) {
    filter(ThresholdFilter) {
        level = toLevel(consoleLogLevel, INFO)
    }
    conversionRule("highlightex", LogLevelColorsConverter)
    encoder(PatternLayoutEncoder) {
        pattern = "%d{yyyy-MM-dd HH:mm:ss} %boldWhite(%-10.-10thread) %highlightex(%-5level) %gray(%-25.-25logger{0}) - %msg%n"
    }
}
appender("FILE", RollingFileAppender) {
    file = "${logRootPath}veriblock.nodecore-pop" + (addJsonLogs ? ".json" : ".log")
    rollingPolicy(SizeAndTimeBasedRollingPolicy) {
        fileNamePattern = "${logRootPath}veriblock.nodecore-pop.%d{yyyy-MM-dd}.%i" + (addJsonLogs ? ".json" : ".log")
        maxHistory = 30
        maxFileSize = "10MB"
        totalSizeCap = "1GB"
    }
    if (addJsonLogs) {
        layout(JsonLayout) {
            jsonFormatter(JacksonJsonFormatter)
            appendLineSeparator = true
        }
    } else {
        encoder(PatternLayoutEncoder) {
            pattern = "%date{YYYY-MM-dd HH:mm:ss.SSSXX} %level [%thread] %logger{10} [%file:%line] %msg%n"
        }
    }
}

appender("ERROR-FILE", FileAppender) {
    file = "${logRootPath}veriblock.nodecore-pop-error" + (addJsonLogs ? ".json" : ".log")
    filter(ThresholdFilter) {
        level = ERROR
    }
    if (addJsonLogs) {
        layout(JsonLayout) {
            jsonFormatter(JacksonJsonFormatter)
            appendLineSeparator = true
        }
    } else {
        encoder(PatternLayoutEncoder) {
            pattern = "%date{YYYY-MM-dd HH:mm:ss.SSSXX} %level [%thread] %logger{10} [%file:%line] %msg%n"
        }
    }
}

appender("BITCOINJ-FILE", RollingFileAppender) {
    file = "${logRootPath}bitcoinj.nodecore-pop" + (addJsonLogs ? ".json" : ".log")
    rollingPolicy(SizeAndTimeBasedRollingPolicy) {
        fileNamePattern = "${logRootPath}bitcoinj.nodecore-pop.%d{yyyy-MM-dd}.%i" + (addJsonLogs ? ".json" : ".log")
        maxHistory = 10
        maxFileSize = "10MB"
        totalSizeCap = "100MB"
    }
    if (addJsonLogs) {
        layout(JsonLayout) {
            jsonFormatter(JacksonJsonFormatter)
            appendLineSeparator = true
        }
    } else {
        encoder(PatternLayoutEncoder) {
            pattern = "%date{YYYY-MM-dd HH:mm:ss.SSSXX} %level [%thread] %logger{10} [%file:%line] %msg%n"
        }
    }
}

logger("org.veriblock.miners.pop", toLevel(logLevel, DEBUG))

logger("org.bitcoinj", INFO, ["BITCOINJ-FILE"], false)
logger("shell-printing", INFO, ["FILE"], false)
logger("Exposed", ERROR)

root(ERROR, ["TERMINAL", "FILE", "ERROR-FILE"])
