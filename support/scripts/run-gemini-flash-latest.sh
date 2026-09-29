#!/bin/bash
# please ensure to load the API key as well in the application.yml or via arguments below

mvn spring-boot:run -Dspring-boot.run.arguments="--openworker.models.default-provider=google-genai --openworker.models.default-model=gemini-2.5"
