import re
import os

artifact_path = "/Users/sharjeelashraf/.gemini/antigravity/brain/08a2635e-934b-4add-9ee0-f887dddcd669/dashcam_app_guide.md"
with open(artifact_path, 'r') as f:
    content = f.read()

# Define the files and their relative paths
files = {
    "app/build.gradle.kts": r"// app/build.gradle\.kts\n(.*?)\n```",
    "app/src/main/AndroidManifest.xml": r"```xml\n(<\?xml version.*?)\n```",
    "app/src/main/java/com/example/dashcam/Constants.kt": r"package com\.example\.dashcam\n\nobject Constants \{.*?\n\}",
    "app/src/main/java/com/example/dashcam/StorageManager.kt": r"package com\.example\.dashcam\n\nimport android\.content\.ContentUris.*?private data class VideoItem\(val id: Long, val size: Long, val dateAdded: Long\)\n\}",
    "app/src/main/java/com/example/dashcam/CleanupWorker.kt": r"package com\.example\.dashcam\n\nimport android\.content\.Context.*?return Result\.success\(\)\n    \}\n\}",
    "app/src/main/java/com/example/dashcam/DashcamService.kt": r"package com\.example\.dashcam\n\nimport android\.annotation\.SuppressLint.*?\nclass DashcamService : LifecycleService\(\) \{.*?\n    \}\n\}",
    "app/src/main/java/com/example/dashcam/MainActivity.kt": r"package com\.example\.dashcam\n\nimport android\.Manifest.*?\nclass MainActivity : ComponentActivity\(\) \{.*?\n    \}\n\}"
}

for path, pattern in files.items():
    match = re.search(pattern, content, re.DOTALL)
    if match:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w') as f:
            if len(match.groups()) > 0:
                f.write(match.group(1))
            else:
                f.write(match.group(0))
        print(f"Extracted {path}")
    else:
        print(f"Failed to extract {path}")
