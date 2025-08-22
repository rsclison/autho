import requests
import json

def check_authorization(subject, resource, operation):
    """
    Sends an authorization request to the server.
    """
    url = "http://localhost:8080/isAuthorized"
    request_body = {
        "subject": subject,
        "resource": resource,
        "operation": operation
    }
    headers = {"Content-Type": "application/json"}

    try:
        response = requests.post(url, data=json.dumps(request_body), headers=headers)
        response.raise_for_status()  # Raise an exception for bad status codes
        return response.json()
    except requests.exceptions.RequestException as e:
        print(f"Error connecting to the server: {e}")
        return None

if __name__ == "__main__":
    # Example 1: Should be authorized
    subject1 = {"class": "Person", "role": "professeur"}
    resource1 = {"class": "Diplome"}
    operation1 = "lire"

    print("Checking authorization for:")
    print(f"  Subject: {subject1}")
    print(f"  Resource: {resource1}")
    print(f"  Operation: {operation1}")
    response1 = check_authorization(subject1, resource1, operation1)
    print(f"Response: {response1}")

    print("-" * 20)

    # Example 2: Should be denied
    subject2 = {"class": "Person", "role": "etudiant"}
    resource2 = {"class": "Diplome"}
    operation2 = "lire"

    print("Checking authorization for:")
    print(f"  Subject: {subject2}")
    print(f"  Resource: {resource2}")
    print(f"  Operation: {operation2}")
    response2 = check_authorization(subject2, resource2, operation2)
    print(f"Response: {response2}")
