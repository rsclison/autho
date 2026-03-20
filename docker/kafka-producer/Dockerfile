FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY produce.py .

ENV KAFKA_BOOTSTRAP=kafka:29092

ENTRYPOINT ["python", "produce.py"]
CMD ["--bootstrap", "kafka:29092", "--all", "-n", "20"]
