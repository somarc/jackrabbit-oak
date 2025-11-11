FROM eclipse-temurin:11-jre

# Install curl for healthchecks
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Create app directory
WORKDIR /app

# Copy uber JAR file (shaded with all dependencies)
COPY target/oak-segment-consensus.jar /app/oak-segment-consensus.jar

# Expose HTTP port
EXPOSE 8090

# Set default environment variables
ENV JAVA_OPTS=""

# Run the global store server
CMD java $JAVA_OPTS -jar /app/oak-segment-consensus.jar \
    --port 8090 \
    --store /var/oak-chain/segmentstore-composite-mount-oak-chain

