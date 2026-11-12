# Runtime-only image. Build the executable JAR first (./mvnw verify), then build this image.
# Same layout as the KOIKI D3 container verification: layered extract, non-root, SIGTERM.
# Override JAVA_RUNTIME_IMAGE (e.g. with a registry mirror) only with the same pinned digest.
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:21-jre-noble@sha256:0eda5830ed7b669abc246a8dc520ea8a23cab06f9a8d4b6964109e2f2394e46f

FROM ${JAVA_RUNTIME_IMAGE} AS extractor
WORKDIR /builder
COPY target/koiki-java-scaffold.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM ${JAVA_RUNTIME_IMAGE} AS runtime
WORKDIR /app
COPY --from=extractor --chown=10001:10001 /builder/extracted/dependencies/ ./
COPY --from=extractor --chown=10001:10001 /builder/extracted/spring-boot-loader/ ./
COPY --from=extractor --chown=10001:10001 /builder/extracted/snapshot-dependencies/ ./
COPY --from=extractor --chown=10001:10001 /builder/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
