# 构建阶段
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# 运行阶段
# 钉在 Ubuntu 24.04(noble):不带系统名的 21-jre 会跟着官方换到新系统(2026-11-17 换成 26.04 resolute),
# 下面换的中科大源不给 resolute(403),构建直接失败。要升系统时先确认镜像源支持再改这里。
FROM eclipse-temurin:21-jre-noble
WORKDIR /app

# CI 注入构建标识:镜像 tag 固定是 :latest,没有版本号可比,
# 所以拿这个 commit 跟 GitHub 上 main 的最新 commit 比,判断有没有新版本。
# 本地手工构建不传就是 dev,面板一律不提示更新。
ARG BUILD_COMMIT=dev
ARG BUILD_TIME=""
ENV TMS_BUILD_COMMIT=$BUILD_COMMIT
ENV TMS_BUILD_TIME=$BUILD_TIME

ENV JAVA_OPTS="-Xmx512m -Xms256m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
ENV LANG=en_US.UTF-8
ENV LANGUAGE=en_US:en
ENV LC_ALL=en_US.UTF-8

# Ubuntu 24.04 起源配置在 /etc/apt/sources.list.d/ubuntu.sources(deb822),老的 sources.list 只剩注释,
# 只改它等于没换源(2026-11-11 GitHub 构建机解析不了 *.ubuntu.com、连续失败才发现)。两处都换;arm64 走 ports
RUN mkdir -p /tmp/apt-bak \
    && for f in /etc/apt/sources.list /etc/apt/sources.list.d/ubuntu.sources; do \
        [ -f "$f" ] && cp "$f" /tmp/apt-bak/$(basename "$f"); \
    done \
    && replace_mirror() { \
        for f in /etc/apt/sources.list /etc/apt/sources.list.d/ubuntu.sources; do \
            [ -f "/tmp/apt-bak/$(basename "$f")" ] && cp "/tmp/apt-bak/$(basename "$f")" "$f" \
                && sed -i -e "s#http://archive.ubuntu.com#$1#g" \
                          -e "s#http://security.ubuntu.com#$1#g" \
                          -e "s#http://ports.ubuntu.com#$1#g" "$f"; \
        done; \
    } \
    && (apt-get -o Acquire::Retries=3 update \
        || (replace_mirror "http://mirrors.aliyun.com" && apt-get -o Acquire::Retries=3 update) \
        || (replace_mirror "http://mirrors.ustc.edu.cn" && apt-get -o Acquire::Retries=3 update) \
        || (for f in /etc/apt/sources.list /etc/apt/sources.list.d/ubuntu.sources; do \
                [ -f "/tmp/apt-bak/$(basename "$f")" ] && cp "/tmp/apt-bak/$(basename "$f")" "$f"; \
            done && apt-get -o Acquire::Retries=3 update)) \
    && apt-get install -y --no-install-recommends fontconfig fonts-dejavu \
    && apt-get clean \
    && rm -rf /tmp/apt-bak /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar
EXPOSE 6365

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
