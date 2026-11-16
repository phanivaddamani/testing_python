import requests
import re

def paginate_risk_list():
    base_url = "https://latap2-eil.api.cubicnextcloud.com/inspectionopenapi/v1/inspect/risklist?Device=MPV30001"
    current_version = "0"
    iterations = []
    iteration_num = 1

    headers = {
        "Timestamp": "2026-10-30T04:39:53Z",
        "x-cub-hdr": '{"uid":"c7e84b91-38f2-4e61-9f2d-4191d9d92b87","device":"HHU00009"}',
        "Content-Type": "application/json",
        "x-cub-audit": '{"sourceIp":"192.168.10.63","userId":"yeh","firstName":"Cheng","lastName":"Yeh","channel":"TravelerWeb", "location":"Travel WEB"}'
    }
    
    while True:
        url = f"{base_url}&CurrentVersion={current_version}&apikey=zVEsL35ioyquTJl4Zh8JAC72EgHkzZEr"
        print(f"Calling Iteration {iteration_num} with CurrentVersion={current_version}...")
        
        try:
            response = requests.get(url, headers=headers)
            response.raise_for_status()
            response_text = response.text.replace('\r\n', '\n')
        except requests.exceptions.RequestException as e:
            print(f"API request failed: {e}")
            break
        
        # Check if the response contains #MAC footer
        if "#MAC" not in response_text:
            print("No #MAC found or response is empty.")
            print("--- Response Preview ---")
            print(response_text[:500]) # Print first 500 chars for inspection
            print("------------------------")
            break
            
        # Flexible regex: matches any number of quotes (or none) preceding digits at the start of a line
        record_ids = re.findall(r'^["]*(\d+)["]*,?', response_text, re.MULTILINE)
        
        if not record_ids:
            print("0 records found in this response. Stopping.")
            print("--- Last 20 lines of response for debugging ---")
            lines = response_text.strip().split('\n')
            for line in lines[-20:]:
                print(line)
            print("---------------------------------------------")
            break
            
        starting_version = current_version
        ending_version = record_ids[-1]  # Get the last ID
        
        iterations.append({
            "iteration": iteration_num,
            "starting_version": starting_version,
            "ending_version": ending_version,
            "records_retrieved": len(record_ids)
        })
        
        if ending_version == current_version:
            print("Version did not advance. Stopping to prevent infinite loop.")
            break
            
        current_version = ending_version
        iteration_num += 1

    return iterations

if __name__ == "__main__":
    results = paginate_risk_list()
    
    print("\n" + "="*50)
    print(f"Total Iterations: {len(results)}")
    print("="*50)
    for it in results:
        print(f"Iteration {it['iteration']}:")
        print(f"  * Starting Version : {it['starting_version']}")
        print(f"  * Ending Version   : {it['ending_version']}")
        print(f"  * Records Count    : {it['records_retrieved']}")
    print("="*50)