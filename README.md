# iac-pulumi with AWS

Importing Certificate Command through CLI:

aws acm import-certificate --certificate file://certificate.pem \
      --certificate-chain file://ca_bundle.pem \
      --private-key file://private.pem
