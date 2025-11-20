#!/usr/bin/env bash
set -e

###
# 사용법
#  ./build_temi.sh full            # 전체 앱 빌드 + assets 복사 + 테미에 설치
#  ./build_temi.sh photobooth      # 포토부스 전용 빌드(환경변수로 분기) + 복사 + 설치
#  ./build_temi.sh full --no-install       # 빌드 + 복사만, installDebug 생략
#  ./build_temi.sh photobooth --no-copy    # 빌드만 하고 assets 복사/설치 생략
###

MODE=${1:-full}
OPTION=${2:-""}

WEB_ROOT="$HOME/AndroidStudioProjects/TemiNai/TemiNai"
ANDROID_ROOT="$HOME/AndroidStudioProjects/TemiNai"
ASSETS_DIR="$ANDROID_ROOT/app/src/main/assets"

echo "==> Build mode: $MODE"
echo "==> Option: $OPTION"

cd "$WEB_ROOT"

# 1) 리액트 빌드
if [ "$MODE" = "full" ]; then
  echo "==> npm run build (full)"
  npm run build
elif [ "$MODE" = "photobooth" ]; then
  echo "==> npm run build (photobooth only)"
  # Vite/리액트에서 import.meta.env.VITE_BUILD_TARGET 로 분기할 때 사용
  VITE_BUILD_TARGET=photobooth npm run build
else
  echo "Usage: $0 [full|photobooth] [--no-install|--no-copy]"
  exit 1
fi

# 2) dist → 안드로이드 assets 복사 (옵션으로 생략 가능)
if [ "$OPTION" = "--no-copy" ]; then
  echo "==> Skip copying to Android assets (--no-copy)"
else
  echo "==> Copy dist → app/src/main/assets"

  cd "$ANDROID_ROOT"
  rm -rf "$ASSETS_DIR"
  mkdir -p "$ASSETS_DIR"

  # 기본은 dist 전체 복사
  cp -R TemiNai/dist/* "$ASSETS_DIR/"

  # photobooth 모드에서는 불필요한 큰 리소스(영상 등) 삭제 가능
  if [ "$MODE" = "photobooth" ]; then
    echo "==> photobooth mode: removing heavy unused assets (videos, etc.)"
    # 예시: 빌드 후 생성되는 동영상/프로모션 관련 리소스 폴더가 있다면 지우기
    # 실제 경로에 맞게 수정해서 쓰면 됨
    rm -rf "$ASSETS_DIR/assets/videos" 2>/dev/null || true
    rm -rf "$ASSETS_DIR/assets/promo"  2>/dev/null || true
  fi
fi

# 3) 테미에 설치 (옵션으로 생략 가능)
if [ "$OPTION" = "--no-install" ] || [ "$OPTION" = "--no-copy" ]; then
  echo "==> Skip installDebug (option: $OPTION)"
else
  echo "==> ./gradlew installDebug"
  cd "$ANDROID_ROOT"
  ./gradlew installDebug
fi

echo "✅ Done."
