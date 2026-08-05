from pathlib import Path
import json, xml.etree.ElementTree as ET, re, subprocess, tempfile, shutil
ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'android-app'

required=[
    APP/'app/src/main/AndroidManifest.xml',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/MainActivity.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/OnboardingActivity.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahTts.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/OpenAIClient.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/CloudVoiceClient.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/CalmSupport.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/MediaSuggestionEngine.java',
    APP/'.github/workflows/build-apk.yml',
]
for p in required:
    assert p.is_file(), p

for p in (APP/'app/src/main/res').rglob('*.xml'):
    ET.parse(p)
ET.parse(APP/'app/src/main/AndroidManifest.xml')
json.loads((ROOT/'sarah_phone_profile.json').read_text())

prompt=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahPromptBuilder.java').read_text()
for phrase in ['DESTINATION MEDIA SUGGESTIONS','talk about anything','Fiction must never be presented as a reliable travel guide','first flight','age-appropriate','personalized trivia']:
    assert phrase.lower() in prompt.lower(), phrase

main=(APP/'app/src/main/java/com/kiraworld/sarahtravel/MainActivity.java').read_text()
for phrase in ['RecognizerIntent','ACTION_PICK_IMAGES','MemoryExtractor.extract','CloudVoiceClient.speak','showCalmMenu','startTriviaGame']:
    assert phrase in main, phrase

print('STATIC_PACKAGE_VALIDATION_PASS')

onboarding=(APP/'app/src/main/res/layout/activity_onboarding.xml').read_text()
assert 'ageInput' in onboarding
database=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahDatabase.java').read_text()
assert 'DB_VERSION = 3' in database and 'age_group' in database
