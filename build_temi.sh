 # 1) 리액트 빌드
cd ~/AndroidStudioProjects/TemiNai/TemiNai
npm run build

# 2) dist → 안드로이드 assets 복사
cd ~/AndroidStudioProjects/TemiNai
rm -rf app/src/main/assets/*
mkdir -p app/src/main/assets
cp -R TemiNai/dist/* app/src/main/assets/

# 3) 테미에 설치
./gradlew installDebug
# 또는 apk 빌드 후 adb install