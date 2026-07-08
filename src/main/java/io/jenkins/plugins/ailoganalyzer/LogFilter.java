package io.jenkins.plugins.ailoganalyzer;

import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;

public class LogFilter {

    // Regex patterns for identifying critical error/failure lines
    private static final Pattern[] ERROR_PATTERNS = new Pattern[] {
        // Xcode build failure indicators
        Pattern.compile("(?i)error:"),
        Pattern.compile("(?i)fatal error:"),
        Pattern.compile("(?i)\\*\\* BUILD FAILED \\*\\*"),
        Pattern.compile("(?i)Code signing is required"),
        Pattern.compile("(?i)Provisioning profile"),
        Pattern.compile("(?i)No profiles for .* were found"),
        Pattern.compile("(?i)Signing certificate"),
        Pattern.compile("(?i)clang: error"),
        Pattern.compile("(?i)ld: error"),
        Pattern.compile("(?i)Undefined symbols"),
        Pattern.compile("(?i)symbol\\(s\\) not found"),
        Pattern.compile("(?i)PhaseScriptExecution failed"),
        Pattern.compile("(?i)Library not found"),
        Pattern.compile("(?i)Swift compiler error"),
        Pattern.compile("(?i)actool failed"),
        Pattern.compile("(?i)dtrace: failed"),
        
        // Unity build failure indicators
        Pattern.compile("(?i)CompilerError"),
        Pattern.compile("error CS[0-9]+"), // C# compilation error
        Pattern.compile("(?i)Exception:"),
        Pattern.compile("(?i)BuildFailedException"),
        Pattern.compile("(?i)BuildMethodException"),
        Pattern.compile("(?i)Assertion failed"),
        Pattern.compile("(?i)Error building Player"),
        Pattern.compile("(?i)Script compilation errors"),
        Pattern.compile("(?i)DisplayProgressNotification: Build Failed"),
        
        // General build errors
        Pattern.compile("(?i)\\bfailed\\b"),
        Pattern.compile("(?i)\\bfatal\\b"),
        Pattern.compile("(?i)\\bexception\\b")
    };

    // Regex patterns for identifying warnings if no critical errors are found
    private static final Pattern[] WARNING_PATTERNS = new Pattern[] {
        Pattern.compile("(?i)warning:"),
        Pattern.compile("warning CS[0-9]+"),
        Pattern.compile("(?i)\\bwarning\\b")
    };

    // Patterns to exclude (to avoid noise like summary lines saying 0 errors or 0 warnings)
    private static final Pattern[] EXCLUDE_PATTERNS = new Pattern[] {
        Pattern.compile("(?i)\\b0 errors\\b"),
        Pattern.compile("(?i)\\b0 failed\\b"),
        Pattern.compile("(?i)error(s)?: 0"),
        Pattern.compile("(?i)failed: 0"),
        Pattern.compile("(?i)0 compilation errors"),
        Pattern.compile("(?i)0 warnings"),
        Pattern.compile("(?i)0 warning"),
        Pattern.compile("(?i)warning(s)?: 0")
    };

    /**
     * Filters log lines to find high-signal segments related to errors or warnings.
     * 
     * @param rawLines Standard list of lines retrieved from Jenkins log.
     * @param maxContextBefore Number of context lines to include before a matching line.
     * @param maxContextAfter Number of context lines to include after a matching line.
     * @param maxOutputLines The maximum number of lines allowed in the final returned string.
     * @return A consolidated String containing relevant log segments separated by ellipses.
     */
    public static String filterLog(List<String> rawLines, int maxContextBefore, int maxContextAfter, int maxOutputLines) {
        if (rawLines == null || rawLines.isEmpty()) {
            return "[AI Log Analyzer: No log lines provided]";
        }

        int n = rawLines.size();
        boolean[] isSelected = new boolean[n];
        boolean hasAnyMatch = false;

        // TIER 1: Search for critical errors
        for (int i = 0; i < n; i++) {
            String line = rawLines.get(i);
            boolean isError = false;
            
            for (Pattern p : ERROR_PATTERNS) {
                if (p.matcher(line).find()) {
                    isError = true;
                    break;
                }
            }

            if (isError) {
                // Check exclusions
                for (Pattern p : EXCLUDE_PATTERNS) {
                    if (p.matcher(line).find()) {
                        isError = false;
                        break;
                    }
                }
            }

            if (isError) {
                hasAnyMatch = true;
                markContext(isSelected, n, i, maxContextBefore, maxContextAfter);
            }
        }

        // TIER 2: If no critical errors found, look for warnings
        if (!hasAnyMatch) {
            for (int i = 0; i < n; i++) {
                String line = rawLines.get(i);
                boolean isWarning = false;
                
                for (Pattern p : WARNING_PATTERNS) {
                    if (p.matcher(line).find()) {
                        isWarning = true;
                        break;
                    }
                }

                if (isWarning) {
                    for (Pattern p : EXCLUDE_PATTERNS) {
                        if (p.matcher(line).find()) {
                            isWarning = false;
                            break;
                        }
                    }
                }

                if (isWarning) {
                    hasAnyMatch = true;
                    // For warnings, use a smaller context
                    markContext(isSelected, n, i, Math.max(1, maxContextBefore - 1), Math.max(2, maxContextAfter - 2));
                }
            }
        }

        // TIER 3: Fall back to returning the last maxOutputLines directly if no errors/warnings found
        if (!hasAnyMatch) {
            int start = Math.max(0, n - maxOutputLines);
            StringBuilder sb = new StringBuilder();
            sb.append("[AI Log Analyzer: No specific build errors/warnings detected. Showing last ").append(n - start).append(" lines of log.]\n");
            sb.append("--------------------------------------------------------------------------------\n");
            for (int i = start; i < n; i++) {
                sb.append(rawLines.get(i)).append("\n");
            }
            return sb.toString();
        }

        // Build the filtered output string
        StringBuilder sb = new StringBuilder();
        sb.append("[AI Log Analyzer: Pre-filtered high-signal build log. Non-essential info omitted to optimize token usage and processing speed.]\n");
        sb.append("--------------------------------------------------------------------------------\n");
        
        int lastAddedIndex = -1;
        int linesCount = 0;
        
        for (int i = 0; i < n; i++) {
            if (isSelected[i]) {
                // If there's a gap between selected regions, show ellipsis
                if (lastAddedIndex != -1 && i > lastAddedIndex + 1) {
                    sb.append("[... ").append(i - lastAddedIndex - 1).append(" lines omitted ...]\n");
                }
                sb.append(rawLines.get(i)).append("\n");
                lastAddedIndex = i;
                linesCount++;
                
                // Truncate to prevent exceeding max output lines
                if (linesCount >= maxOutputLines) {
                    sb.append("[... Remaining log lines truncated to optimize payload speed ...]\n");
                    break;
                }
            }
        }
        
        return sb.toString();
    }

    private static void markContext(boolean[] isSelected, int n, int targetIndex, int before, int after) {
        int start = Math.max(0, targetIndex - before);
        int end = Math.min(n - 1, targetIndex + after);
        for (int j = start; j <= end; j++) {
            isSelected[j] = true;
        }
    }
}
