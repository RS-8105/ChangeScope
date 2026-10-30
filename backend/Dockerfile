
# ==========================================
# Stage 1: Build Spring Boot Application
# ==========================================
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

# Copy Maven wrapper and project configuration
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Make Maven wrapper executable
RUN chmod +x mvnw

# Download dependencies first for better Docker caching
RUN ./mvnw dependency:go-offline

# Copy application source code
COPY src ./src

# Build the Spring Boot executable JAR
RUN ./mvnw clean package -DskipTests && \
    cp target/*.jar target/app.jar

# ==========================================
# Stage 2: Run Spring Boot Application
# ==========================================
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy executable JAR from build stage
COPY --from=build /app/target/app.jar app.jar

# Render provides the PORT environment variable
EXPOSE 8081

# Start Spring Boot application
ENTRYPOINT ["java", "-jar", "app.jar"]
