# Personal translation credentials

This personal deployment imports the user's own service-account or API-key credentials and encrypts them with an Android Keystore key in app-private, backup-excluded storage. The user explicitly chose direct provider access without a hosted credential broker; this differs from Google's recommended service-account architecture for mobile clients and makes credential deletion, log redaction, and preventing credential backup part of the app's responsibilities.

Reference: [Google service-account key guidance](https://docs.cloud.google.com/iam/docs/best-practices-for-managing-service-account-keys).
