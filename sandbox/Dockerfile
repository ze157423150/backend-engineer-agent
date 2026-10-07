FROM maven:3.9.9-eclipse-temurin-21
WORKDIR /opt/preload
COPY pom.xml .
COPY src ./src
# Trusted preparation downloads dependencies; actual agent tests run offline without a host Maven cache.
RUN mvn -B -ntp dependency:go-offline clean verify \
    && cp target/backend-engineer-agent-0.1.0-SNAPSHOT.jar /opt/agent-tools.jar \
    && chmod a+r /opt/agent-tools.jar \
    && mv /root/.m2/repository /opt/maven-repository \
    && chmod -R a+rX /opt/maven-repository \
    && rm -rf /opt/preload
WORKDIR /workspace
USER 10001:10001
ENTRYPOINT ["mvn"]
