# V51 SMTP egress security note

The SMTP form does not permit arbitrary destinations. `smtp.gmail.com` is the
default allowed host; each custom SMTP server must be added as an exact hostname
to `SIMERTPI_SMTP_ALLOWED_HOSTS` outside the repository. IP literals, localhost,
link-local, private, multicast, and other local destinations are rejected. The
backend resolves and checks the host before connection tests and again before
each invitation send.

JavaMail resolves the hostname again when opening its socket. The application
does not pin that socket to the address already checked, so DNS rebinding between
validation and connection remains a residual risk. The allowlist and repeated
resolution reduce SSRF exposure but do not claim to eliminate it. Do not add an
untrusted custom SMTP host to the external allowlist.
