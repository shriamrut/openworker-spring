#!/bin/bash

mvn spring-boot:run -Dspring-boot.run.arguments="--openworker.models.default-provider=google-genai --openworker.models.default-model=gemini-2.5"
