/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.errorprone;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Processes command-line options specific to error-prone.
 *
 * <p>error-prone lets the user enable and disable specific checks as well as override their
 * built-in severity levels (warning vs. error).
 *
 * <p>A valid error-prone command-line option looks like:<br>
 * <pre>{@code
 * -Xep:<checkName>[:severity]
 * }</pre>
 *
 * <p>{@code checkName} is required and is the canonical name of the check, e.g. "StringEquality".
 * {@code severity} is one of {"OFF", "WARN", "ERROR"}.  Multiple flags must be passed to
 * enable or disable multiple checks.  The last flag for a specific check wins.
 *
 * <p>Examples of usage follow:<br>
 * <pre>{@code
 * -Xep:StringEquality  [turns on StringEquality check with the severity level from its BugPattern
 *                       annotation]
 * -Xep:StringEquality:OFF  [turns off StringEquality check]
 * -Xep:StringEquality:WARN  [turns on StringEquality check as a warning]
 * -Xep:StringEquality:ERROR  [turns on StringEquality check as an error]
 * -Xep:StringEquality:OFF -Xep:StringEquality  [turns on StringEquality check]
 * }</pre>
 *
 * <p>We will continue to support the old-style error-prone disabling flags for a short transition
 * period.  Those flags have the following syntax:<br>
 * <pre>{@code
 * -Xepdisable:<checkName>[,<checkName>...]
 * }</pre>
 *
 * @author eaftan@google.com (Eddie Aftandilian)
 */
public class ErrorProneOptions {

  private static final String FLAG_PREFIX = "-Xep:";
  private static final String OLD_DISABLE_FLAG_PREFIX = "-Xepdisable:";

  /**
   * see {@link javax.tools.OptionChecker#isSupportedOption(String)}
   */
  public static int isSupportedOption(String option) {
    return (option.startsWith(FLAG_PREFIX) || option.startsWith(OLD_DISABLE_FLAG_PREFIX))
        ? 0 : -1;
  }

  /**
   * Severity levels for an error-prone check that define how the check results should be
   * presented.
   */
  public enum Severity {
    DEFAULT,    // whatever is specified in the @BugPattern annotation
    OFF,
    WARN,
    ERROR
  }

  private final ImmutableList<String> remainingArgs;
  private final ImmutableMap<String, Severity> severityMap;

  private ErrorProneOptions(ImmutableMap<String, Severity> severityMap,
      ImmutableList<String> remainingArgs) {
    this.severityMap = severityMap;
    this.remainingArgs = remainingArgs;
  }

  public String[] getRemainingArgs() {
    return remainingArgs.toArray(new String[remainingArgs.size()]);
  }

  public ImmutableMap<String, Severity> getSeverityMap() {
    return severityMap;
  }

  /**
   * Given a list of command-line arguments, produce the corresponding {@link ErrorProneOptions}
   * instance.
   *
   * @param args command-line arguments
   * @return an {@link ErrorProneOptions} instance encapsulating the given arguments
   * @throws InvalidCommandLineOptionException if an error-prone option is invalid
   */
  public static ErrorProneOptions processArgs(Iterable<String> args)
      throws InvalidCommandLineOptionException {
    Preconditions.checkNotNull(args);
    ImmutableList.Builder<String> outputArgs = ImmutableList.builder();
    Map<String, Severity> severityMap = new HashMap<>();

    FlagStyle flagStyle = getFlagStyle(args);
    switch (flagStyle) {
      case OLD:
        for (String arg : args) {
          if (arg.startsWith(OLD_DISABLE_FLAG_PREFIX)) {
            String[] checksToDisable = arg.substring(OLD_DISABLE_FLAG_PREFIX.length()).split(",");
            severityMap = new HashMap<>();
            for (String checkName : checksToDisable) {
              severityMap.put(checkName, Severity.OFF);
            }
          } else {
            outputArgs.add(arg);
          }
        }
        break;
      case NEW:
        for (String arg : args) {
          if (arg.startsWith(FLAG_PREFIX)) {
            // Strip prefix
            String remaining = arg.substring(FLAG_PREFIX.length());
            // Split on ':'
            String[] parts = remaining.split(":");
            if (parts.length > 2 || parts[0].isEmpty()) {
              throw new InvalidCommandLineOptionException("invalid flag: " + arg);
            }
            String checkName = parts[0];
            Severity severity;
            if (parts.length == 1) {
              severity = Severity.DEFAULT;
            } else {  // parts.length == 2
              try {
                severity = Severity.valueOf(parts[1]);
              } catch (IllegalArgumentException e) {
                throw new InvalidCommandLineOptionException("invalid flag: " + arg);
              }
            }
            severityMap.put(checkName, severity);
          } else {
            outputArgs.add(arg);
          }
        }
        break;
      case NEITHER:
        outputArgs.addAll(args);
        break;
      default:
        throw new IllegalStateException("Unhandled enum value: " + flagStyle);
    }

    return new ErrorProneOptions(ImmutableMap.copyOf(severityMap), outputArgs.build());
  }

  /**
   * Given a list of command-line arguments, produce the corresponding {@link ErrorProneOptions}
   * instance.
   *
   * @param args command-line arguments
   * @return an {@link ErrorProneOptions} instance encapsulating the given arguments
   * @throws InvalidCommandLineOptionException if an error-prone option is invalid
   */
  public static ErrorProneOptions processArgs(String[] args)
      throws InvalidCommandLineOptionException {
    Preconditions.checkNotNull(args);
    return processArgs(Arrays.asList(args));
  }

  private enum FlagStyle {
    OLD,
    NEW,
    NEITHER
  }

  /**
   * Checks the list of command-line arguments and returns whether they contain old-style
   * ("-Xepdisable:<checkname>"), new-style ("-Xep:<checkname>[:<severity]"), or no error-prone
   * flags.
   *
   * <p>Mixing old- and new-style error-prone flags is not allowed.  If you mix them, the handling
   * of "last flag wins" is unclear.
   *
   * @param args command-line options
   * @throws InvalidCommandLineOptionException if args contains both old- and new-style flags
   */
  private static FlagStyle getFlagStyle(Iterable<String> args)
      throws InvalidCommandLineOptionException {
    boolean hasOldStyleFlags = false;
    boolean hasNewStyleFlags = false;

    if (args != null) {
      for (String arg : args) {
        if (arg.startsWith(OLD_DISABLE_FLAG_PREFIX)) {
          hasOldStyleFlags = true;
        } else if (arg.startsWith(FLAG_PREFIX)) {
          hasNewStyleFlags = true;
        }
      }
    }

    if (hasOldStyleFlags && hasNewStyleFlags) {
      throw new InvalidCommandLineOptionException(
          "cannot mix old- and new-style error-prone flags: " + args);
    }

    if (hasOldStyleFlags) {
      return FlagStyle.OLD;
    }

    if (hasNewStyleFlags) {
      return FlagStyle.NEW;
    }

    return FlagStyle.NEITHER;
  }
}