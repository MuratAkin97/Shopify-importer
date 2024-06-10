# Use an official Java runtime as a parent image
FROM openjdk:11-jre-slim

# Set the working directory in the container
WORKDIR /app

# Copy the JAR file into the container
COPY target/shopifyImportScript-1.0-SNAPSHOT.jar /app/shopifyImportScript-1.0-SNAPSHOT.jar

# Pass environment variables
ENV SHOPIFY_API_KEY=$SHOPIFY_API_KEY
ENV SHOPIFY_API_SECRET=$SHOPIFY_API_SECRET
ENV ACCESS_TOKEN=$ACCESS_TOKEN

# Run the Java application
CMD ["java", "-jar", "/app/shopifyImportScript-1.0-SNAPSHOT.jar"]
