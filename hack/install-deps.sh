#!/usr/bin/env bash

set -euo pipefail
mkdir -p bin

echo "installing addlicense@$ADDLICENSE_VERSION to $(go env GOPATH)/bin"
go install "github.com/google/addlicense@$ADDLICENSE_VERSION"

echo "installing oras@$ORAS_VERSION to $(pwd)/bin"
curl -sLO "https://github.com/oras-project/oras/releases/download/v${ORAS_VERSION}/oras_${ORAS_VERSION}_${OS}_${ARCH}.tar.gz"
mkdir -p oras-install/
tar -zxf oras_"${ORAS_VERSION}"_*.tar.gz -C oras-install/
mv oras-install/oras bin/
rm -r oras_"${ORAS_VERSION}"_*.tar.gz oras-install

if [ "$ZARF_VERSION" = "latest" ]; then
  ZARF_VERSION=$(curl -sIX HEAD https://github.com/zarf-dev/zarf/releases/latest | grep -i ^location: | grep -Eo 'v[0-9]+.[0-9]+.[0-9]+')
fi

title() {
    string="$1"
    first_char=$(printf %.1s "$string" | tr '[:lower:]' '[:upper:]')
    rest_of_string=$(printf %s "$string" | cut -c 2-)
    printf '%s%s\n' "$first_char" "$rest_of_string"
}

echo "installing zarf@$ZARF_VERSION to $(pwd)/bin"
curl -sL "https://github.com/zarf-dev/zarf/releases/download/${ZARF_VERSION}/zarf_${ZARF_VERSION}_$(title "$OS")_${ARCH}" -o bin/zarf
chmod +x bin/zarf
