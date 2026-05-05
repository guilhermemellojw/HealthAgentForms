
import sys
import re

def count_braces(file_path):
    with open(file_path, 'r') as f:
        content = f.read()
    
    # Remove single-line comments
    content = re.sub(r'//.*', '', content)
    # Remove multi-line comments
    content = re.sub(r'/\*.*?\*/', '', content, flags=re.DOTALL)
    # Remove string literals (handling escaped quotes)
    content = re.sub(r'"(\\.|[^"\\])*"', '""', content)
    
    lines = content.split('\n')
    count = 0
    for i, line in enumerate(lines):
        for char in line:
            if char == '{':
                count += 1
            elif char == '}':
                count -= 1
        
        print(f"{i+1}: {count}")

if __name__ == "__main__":
    count_braces(sys.argv[1])
