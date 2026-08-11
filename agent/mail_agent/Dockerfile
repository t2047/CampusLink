FROM python:3.12-slim

WORKDIR /app

COPY agent/mail_agent/pyproject.toml ./pyproject.toml
COPY agent/mail_agent/mail_agent ./mail_agent

RUN pip install --no-cache-dir .

# Port 5000 matches the OAuth redirect URI registered in Google Cloud Console
# (http://localhost:5000/callback) so the /callback exchange works in dev.
EXPOSE 5000

CMD ["uvicorn", "mail_agent.main:app", "--host", "0.0.0.0", "--port", "5000"]