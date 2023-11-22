// Copyright (c) 2018, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes.utils;


/**
 * Class that holds the results of using java to exec a command (i.e. exit value, stdout and stderr)
 */
public record ExecResult(int exitValue, String stdout, String stderr) {
  /**
   * Populate execution result.
   *
   * @param exitValue Exit value
   * @param stdout    Contents of standard out
   * @param stderr    Contents of standard error
   */
  public ExecResult {
  }

  @Override
  public String toString() {
    return String.format(
        "ExecResult: exitValue = %s, stdout = %s, stderr = %s",
        exitValue,
        stdout,
        stderr);
  }
}
