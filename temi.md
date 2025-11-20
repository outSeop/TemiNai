## 폴더 구조
TemiNai						`# 최상위 프로젝트(자바)`
├── TemiNai					`# react 프로젝트`
│   ├── README.md	
│   ├── dist				
│   ├── eslint.config.js
│   ├── index.html
│   ├── node_modules
│   ├── package-lock.json
│   ├── package.json
│   ├── postcss.config.js
│   ├── public
│   ├── server
│   ├── src
│   ├── tailwind.config.js
│   └── vite.config.js
├── app
│   ├── build
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src					`# 자바 코드`
├── build
│   └── reports
├── build.gradle.kts
├── build_temi.sh			`# react build + java build + install to temp`
├── gradle
│   ├── libs.versions.toml
│   └── wrapper
├── gradle.properties
├── gradlew
├── gradlew.bat
├── local.properties
├── log.txt
└── settings.gradle.kts

## react project
React TemiNai 폴더의 경우 내부적로 git이 있으니 수정사항 있으면 react 프로젝트 TemiNai 폴더 내에서 git 활용하시면 됩니다!

## build
`build_temi.sh` 사용 전
1. 테미 개발자 모드에서 포트 열기
2. 해당 ip로 `adb connect {ip}:5555`
3. 완료 시 `connected to {ip}:5555` 출력. (Ex 192.168.0.2:5555)
4. 확인이 필요하면 `adb devices`, 해당 ip 옆에 device가 떠있으면 연결된 상태
`build_temi.sh` 실행 시 react build 실행 이후 나온 파일들을 복사해서 자바 프로젝트 내에 있는 assets 폴더에 덮어 씌웁니다. 그 후 java build -> temi에 앱 다운 순으로 진행됩니다.

중간에 java assets 경로에 파일 삭제한다는 알림이 뜨는데 y 입력하셔서 삭제 후 이동시키면 됩니다.

테미 내 로그는 `adb logcat`으로 확인 가능합니다. 초반에 긴 로그 출력하느라 뭐가 막 올라갈텐데 조금 기다리시고 기능 사용해보면서 로깅해보시면 됩니다.

## java
자바는 코드는 “/TemiNai/app/src/main/java/com/example/teminai/MainActivity.java”에 위치해있습니다.

길찾기에 경우 `// 길찾기 매핑` 주석아래 map에서 key는 react에서 설정한 id, value는 테미에서 설정한 location 이름입니다.