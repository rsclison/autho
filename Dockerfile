# Use an official Clojure image as a parent image
FROM clojure:lein-2.9.6-slim-buster

# Set the working directory in the container
WORKDIR /usr/src/app

# Copy the project definition file
COPY project.clj .

# Fetch and cache dependencies
RUN lein deps

# Copy the rest of the application source code
COPY . .

# Expose the port the app runs on
EXPOSE 8080

# Define the command to run the application
CMD ["./lein", "run"]
