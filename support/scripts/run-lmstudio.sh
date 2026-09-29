#!/bin/bash

export MODEL="nvidia/nemotron-3-nano-4b"
echo $MODEL
mvn spring-boot:run -Dspring-boot.run.arguments="--openworker.models.default-provider=openai --openworker.models.default-model=${MODEL}"
