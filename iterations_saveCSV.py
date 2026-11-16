import re
import requests

def paginate_and_export_csv(output_filename="risk_list_output.csv"):
    base_url = "https://latap2-eil.api.cubicnextcloud.com/inspectionopenapi/v1/inspect/risklist?Device=MPV30001"
    current_version = "0"
    iterations = []
    iteration_num = 1
    
    # Store all unique CSV lines across iterations
    all_csv_lines = []

    headers = {
        "Timestamp": "2026-10-30T04:39:53Z",
        "x-cub-hdr": '{"uid":"c7e84b91-38f2-4e61-9f2d-4191d9d92b87","device":"HHU00005"}',
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
        
        # Check if response contains the expected MAC footer
        if "#MAC" not in response_text:
            print("No #MAC found or response is empty.")
            break
            
        lines = response_text.strip().split('\n')
        
        # Filter lines that represent valid records (lines starting with record ID digits)
        # Excludes headers, footers (#MAC), or metadata metadata lines
        page_records = [line for line in lines if re.match(r'^["]*\d+["]*,?', line)]
        
        if not page_records:
            print("0 records found in this response. Stopping.")
            break
            
        # Collect lines for the final CSV
        all_csv_lines.extend(page_records)
        
        # Extract record IDs to get the ending version for pagination tracking
        record_ids = re.findall(r'^["]*(\d+)["]*,?', response_text, re.MULTILINE)
        ending_version = record_ids[-1]
        
        iterations.append({
            "iteration": iteration_num,
            "starting_version": current_version,
            "ending_version": ending_version,
            "records_retrieved": len(page_records)
        })
        
        if ending_version == current_version:
            print("Version did not advance. Stopping to prevent infinite loop.")
            break
            
        current_version = ending_version
        iteration_num += 1

    # Save all accumulated records to a CSV file
    if all_csv_lines:
        with open(output_filename, "w", encoding="utf-8") as f:
            for line in all_csv_lines:
                f.write(line + "\n")
        print(f"\nSuccessfully saved {len(all_csv_lines)} total records to '{output_filename}'.")
    else:
        print("No records retrieved to write.")

    return iterations

if __name__ == "__main__":
    results = paginate_and_export_csv()
    
    print("\n" + "="*50)
    print(f"Total Iterations: {len(results)}")
    print("="*50)
    for it in results:
        print(f"Iteration {it['iteration']}:")
        print(f"  * Starting Version : {it['starting_version']}")
        print(f"  * Ending Version   : {it['ending_version']}")
        print(f"  * Records Count    : {it['records_retrieved']}")
    print("="*50)