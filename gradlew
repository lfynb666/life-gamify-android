#!/bin/sh

# Gradle启动脚本（简化版，CI环境会自动下载完整wrapper）

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# 检查是否有wrapper jar，没有则下载
if [ ! -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
    echo "Gradle wrapper jar不存在，使用系统gradle或请先运行: gradle wrapper --gradle-version 8.5"
    # 尝试用系统gradle
    if command -v gradle >/dev/null 2>&1; then
        exec gradle "$@"
    fi
    echo "请安装Gradle或下载gradle-wrapper.jar"
    exit 1
fi

exec java $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
