# TDR Draft Metadata Validator

This repository contains the **TDR Draft Metadata Validator**, an AWS Lambda function responsible for validating consignment metadata files in CSV format. The Lambda is invoked with a single parameter, `consignmentId`, which is used to locate the metadata for a specific consignment. The Lambda performs validation on the metadata file, processes any errors, and updates the consignment status accordingly.

## Overview

The **TDR Draft Metadata Validator** is a core component of the **Transfer Digital Records (TDR)** system, ensuring that metadata files for consignments meet specific requirements. The function performs the following actions:

1. Downloads a `draft-metadata.csv` file from an AWS S3 bucket, with the key determined from the `consignmentId`.
2. Validates the file against several rules:
    - Checks if the file is UTF-8 encoded.
    - Ensures it is in valid CSV format.
    - Validates required columns and the CSV's structure against predefined JSON schemas.
3. Writes a JSON file containing any validation errors to an S3 bucket.
4. If no errors are found, the metadata is saved to the database.
5. Updates the consignment status:
    - **Completed** if the metadata is valid.
    - **Completed with Issues** if there were validation errors.

## Function Workflow

1. **Invoke the Lambda**: The function is triggered by passing a `consignmentId` parameter.
2. **Download Metadata**: The Lambda retrieves the `metadata.csv` file from an S3 bucket based on the `consignmentId`.
3. **Validate the File**:
    - Check the file encoding (must be UTF-8).
    - Validate that it is a well-formed CSV file.
    - Ensure required columns exist and match the structure defined in the JSON schemas.
4. **Error Handling**:
    - If any validation fails, the Lambda creates an error report in JSON format.
    - The JSON file is saved to S3, under the consignment folder, for easy access.
5. **Persist Data**:
    - If the file passes validation, the Lambda saves the metadata to the database.
6. **Update Consignment Status**:
    - The consignment status is updated based on the validation result: either **Completed** (if no errors) or **CompletedWithIssues** (if errors were found).

## Input

The Lambda function is invoked with a single input parameter:
- **`consignmentId`**: A unique identifier representing the consignment whose metadata needs to be validated.

## Outputs

1. **S3 Uploads**:
    - A JSON file is written to the consignment's folder in S3 if there are validation errors.

2. **Database Updates**:
    - If the metadata passes validation, the metadata is persisted to the database.

3. **Consignment Status**:
    - The consignment status is updated to reflect the validation result. The statuses can be:
        - **Completed**: All validations passed successfully.
        - **CompletedWithIssues**: Validation issues were found, but the process completed.

## Error Handling

If an error occurs during any validation step, there will be no further validation steps, but the failure creates an error  that will be saved.  A detailed error report is generated and uploaded to AWS S3 in JSON format for further analysis. The consignment status will reflect any issues encountered.

## Deployment

This Lambda function is designed to be deployed within the AWS ecosystem. The necessary infrastructure, such as S3 buckets, database connections, and environment configurations, should be pre-configured.

### Prerequisites

- **AWS S3**: An S3 bucket for storing the consignment's metadata file and any error reports.
- **Database**: A database connection for persisting the validated metadata.
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
