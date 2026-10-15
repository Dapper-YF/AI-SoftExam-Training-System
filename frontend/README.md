# Frontend Baseline

## Environment Security

Vite exposes every `VITE_*` environment variable to application code and compiles its value into the browser bundle. These variables are public configuration, not a secret store. Never place real identifiers, passwords, access tokens, admission tickets, identity numbers, or other sensitive data in any `VITE_*` variable.

Production builds use HTTP gateways by default. `VITE_PLATFORM_MOCK` and `VITE_EXAM_SIMULATOR_MOCK` are disabled unless their value is exactly `true`.

For a local Mock exercise, create an uncommitted `.env.local` and explicitly enable only the required Mock gateway:

```dotenv
VITE_PLATFORM_MOCK=true
VITE_EXAM_SIMULATOR_MOCK=true
VITE_MOCK_STUDENT_IDENTIFIER=<local-only-phone-or-email>
VITE_MOCK_STUDENT_PASSWORD=<local-only-password>
VITE_MOCK_STUDENT_DISPLAY_NAME=<local-only-display-name>
VITE_MOCK_ADMIN_IDENTIFIER=<local-only-phone-or-email>
VITE_MOCK_ADMIN_PASSWORD=<local-only-password>
VITE_MOCK_ADMIN_DISPLAY_NAME=<local-only-display-name>
```

No usable Mock password is provided by the repository. Keep local values synthetic and never commit `.env.local`.
