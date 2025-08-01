FROM  sbtscala/scala-sbt:eclipse-temurin-focal-17.0.9_9_1.9.8_3.3.1 as builder
COPY . /lambda/src/
WORKDIR /lambda/src/
RUN sbt assembly

FROM public.ecr.aws/lambda/java:17
COPY --from=builder /lambda/src/lambdas/tdr-draft-metadata-validator/target/scala-2.13/tdr-draft-metadata-validator.jar ${LAMBDA_TASK_ROOT}/lib/
CMD ["uk.gov.nationalarchives.tdr.draftmetadatavalidator.Lambda::handleRequest"]
