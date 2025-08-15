# TDR Draft Metadata Validator

This repository contains the **TDR Draft Metadata Validator**, an AWS Lambda function responsible for validating consignment metadata file (draft-metadata.csv) provided in CSV format. The Lambda is invoked with a single parameter, `consignmentId`, which is used to generate the key `consignmentId\draft-metadata-csv` to locate the metadata for a specific consignment. The Lambda performs validation on the metadata file, processes any errors, and updates the consignment status accordingly.

## Overview

The **TDR Draft Metadata Validator** is a core component of the **Transfer Digital Records (TDR)** system, ensuring that metadata file for consignments meets specific requirements. The function performs the following actions:

1. Downloads the `draft-metadata.csv` file from an AWS S3 bucket, with the generated key.
2. Validates the file against several rules:
    - Checks if the file is UTF-8 encoded.
    - Ensures it is in valid CSV format. (Valid if it can be loaded with CSV software 
    - Validates required columns (using a required JSON schema)
    - Validates the content against predefined JSON schemas.
3. Writes a JSON file containing any validation errors to an S3 bucket (this file will also be written if there are no errors)
4. If no errors are found, the metadata is saved to the database.
5. Updates the consignment status:
    - **Completed** if the metadata is valid.
    - **CompletedWithIssues** if there were validation errors.
6.  Returns an `APIGatewayProxyResponseEvent` with status code 200 or 500 if there is an unexpected failure in the Lambda 

## Function Workflow

1. **Invoke the Lambda**: The function is triggered by passing a `consignmentId` parameter.
2. **Download Metadata**: The Lambda retrieves the `metadata.csv` file from an S3 bucket based on the `consignmentId`.
3. **Validate the File**:
    - Check the file encoding (must be UTF-8).
    - Validate that it is a well-formed CSV file.
    - Ensure required columns exist and match the structure defined in the JSON schemas.
4. **Error Handling**:
    - A validation json file is created by the Lambda for each invocation in JSON format.
    - The JSON file is saved to S3, under the consignment folder, for easy access.
5. **Persist Data**:
    - If the file passes validation, the Lambda saves the metadata to the database.
6. **Update Consignment Status**:
    - The consignment status is updated based on the validation result: either **Completed** (if no errors) or **CompletedWithIssues** (if errors were found).
7. **Return APIGatewayProxyResponseEvent**
    - The current response has the status of 200 and no other data   

## Input

The Lambda function is invoked with a single input parameter:
- **`consignmentId`**: A unique identifier representing the consignment whose metadata needs to be validated.

## Outputs

1. **S3 Uploads**:
    - A JSON file `draft-metadata-errors.json` is written to the consignment's folder in S3 after validation.

2. **Database Updates**:
    - If the metadata passes validation, the metadata is persisted to the database.

3. **Consignment Status**:
    - The consignment status is updated to reflect the validation result. The statuses can be:
        - **Completed**: All validations passed successfully.
        - **CompletedWithIssues**: Validation issues were found, but the process completed.
4. **APIGatewayProxyResponseEvent**: 

## Error Handling

If an error occurs during any validation step, there will be no further validation steps, but the failure creates an error that will be saved.  A detailed error report is generated and uploaded to AWS S3 in JSON format for further analysis. The consignment status will reflect any issues encountered. Individual validation is performed uisng Cats Effects IO error handling.

## Deployment

This Lambda function is designed to be deployed as a docker image within the AWS ecosystem. The necessary infrastructure, such as S3 buckets, database connections, and environment configurations, should be pre-configured.

### Prerequisites

- **AWS S3**: An S3 bucket for storing the consignment's metadata file and any error reports.
- **Database Api**: A database connection for persisting the validated metadata.
- **AWS Lambda**: The function is built and deployed as an AWS Lambda function.

### Environment Variables

Ensure the following environment variables are configured for the Lambda function:

- **BUCKET_NAME**: The name of the S3 bucket where metadata and error files are stored.
- **API_URL**: The connection string for the database to persist valid metadata.
- **CLIENT_SECRET_PATH**: 

## Development

### Technologies

- **Scala**: The Lambda function is written in Scala using functional programming principles.
- **cats-effect**: For managing side effects and asynchronous computations within a pure functional environment.
- **AWS SDK**: For interacting with AWS services like S3.

### Running Tests

Unit tests are included to validate the core functionality of the Lambda. You can run the tests locally using `sbt`:

```
sbt test
```

## Notes
The graphql input size has a limit of 8388608 bytes, which is approximately 8MB. Ensure that individual graphql input mutations do not exceed this limit to avoid errors during processing.

If the graphql input mutation is larger than this limit, consider decreasing the `BATCH_SIZE_FOR_METADATA` value.
