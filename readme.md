
Running locally and on AWS cloud (woohoo!)

**Endpoints**
- api/v1/ping
- api/v1/plaid-institution-uptime/{ins_id}
- api/v1/plaid-dashboard
- api/v1/mock-plaid-dashboard



*Cloud:*

To deploy, follow instructions on laaplace deploy.sh scripts

Or upload a new application version using the AWS console




*To run locally:*

Set a PLAID_PUBLIC_KEY environment variable (this can be found in secrets manager)

The PLAID_PUBLIC_KEY must be for a development environment

lein run



check out swagger!
