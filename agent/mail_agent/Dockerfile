FROM python:3.12-slim

WORKDIR /app

COPY agent/mail_agent/pyproject.toml ./pyproject.toml
COPY agent/mail_agent/mail_agent ./mail_agent

RUN pip install --no-cache-dir .

EXPOSE 8091

CMD ["uvicorn", "mail_agent.main:app", "--host", "0.0.0.0", "--port", "8091"]
