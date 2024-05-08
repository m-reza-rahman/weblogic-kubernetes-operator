// Copyright (c) 2024, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.utils;

import java.util.Properties;

import static oracle.kubernetes.operator.BaseMain.GIT_BRANCH_KEY;
import static oracle.kubernetes.operator.BaseMain.GIT_BUILD_TIME_KEY;
import static oracle.kubernetes.operator.BaseMain.GIT_BUILD_VERSION_KEY;
import static oracle.kubernetes.operator.BaseMain.GIT_COMMIT_KEY;

public class PropertiesUtils {
    private static final Properties buildProperties;

    public static final String GIT_BUILD_VERSION = "3.1.0";
    public static final String GIT_BRANCH = "master";
    public static final String GIT_COMMIT = "a987654";
    public static final String GIT_BUILD_TIME = "Sep-10-2015";
    public static final String IMPL = GIT_BRANCH + "." + GIT_COMMIT;

    static {
        buildProperties = new PropertiesBuilder()
                .withProperty(GIT_BUILD_VERSION_KEY, GIT_BUILD_VERSION)
                .withProperty(GIT_BRANCH_KEY, GIT_BRANCH)
                .withProperty(GIT_COMMIT_KEY, GIT_COMMIT)
                .withProperty(GIT_BUILD_TIME_KEY, GIT_BUILD_TIME)
                .build();
    }

    public static Properties getBuildProperties() {
        return buildProperties;
    }

    private static class PropertiesBuilder {
        private final Properties properties = new Properties();

        private PropertiesBuilder withProperty(String name, String value) {
            properties.put(name, value);
            return this;
        }

        private Properties build() {
            return properties;
        }
    }

}
