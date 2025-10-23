# Use Eclipse Temurin with Java 21 as the base image
FROM eclipse-temurin:21

# Define version
ARG VERSION

# Set the working directory inside the container
WORKDIR /app

# Copy Distribution TAR file
COPY --from=distributions helidon-pbj-capacity-test-2-${VERSION}.tar .

# Extract the TAR file
RUN tar -xvf helidon-pbj-capacity-test-2-${VERSION}.tar

# Expose the port that the application will run on
EXPOSE 8082

# RUN the bin script for starting the server
ENTRYPOINT ["sh", "-c", "/app/helidon-pbj-capacity-test-2-1.0-SNAPSHOT/bin/helidon-pbj-capacity-test-2"]


