#!/bin/bash
# Runs OpenWorker with Google Gemini (gemini-2.5-flash-latest) as the default model.
# The GEMINI_API_KEY env var is passed directly as a Spring Boot argument.
# You can also set it in application.yml under spring.ai.google.genai.api-key.

export GEMINI_MODEL="${GEMINI_MODEL:-gemini-2.5}"

mvn spring-boot:run -Dspring-boot.run.arguments="\
  --openworker.models.default-provider=google-genai \
  --openworker.models.default-model=${GEMINI_MODEL} \
  --spring.ai.google.genai.api-key=${GEMINI_API_KEY}"
