{
  inputs = {
    typelevel-nix.url = "github:typelevel/typelevel-nix";
    nixpkgs.url = "nixpkgs/nixos-unstable"; # NOTE we need latest curl
    flake-utils.follows = "typelevel-nix/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils, typelevel-nix }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ typelevel-nix.overlay ];
        };
        curl = pkgs.curl.overrideAttrs (old: {
          # Websocket support in curl is currently in experimental mode
          # which needs to be enabled explicitly in build time in order to be available
          #
          #https://github.com/curl/curl/blob/f8da4f2f2d0451dc0a126ae3e5077b4527ccdc86/configure.ac#L174
          configureFlags = old.configureFlags ++ [ "--enable-websockets" ];
        });

        mkShell = jdk:
          pkgs.devshell.mkShell {
            imports = [ typelevel-nix.typelevelShell ];
            name = "http4s-curl";
            typelevelShell = {
              jdk.package = jdk;
              native = {
                enable = true;
                libraries = [ curl ];
              };
            };
            packages = [ curl ];
            env = [{
              name = "LD_LIBRARY_PATH";
              eval = "$LIBRARY_PATH";
            }];
          };
      in {
        devShell = mkShell pkgs.jdk8;

        devShells = {
          "temurin@8" = mkShell pkgs.temurin-bin-8;
          "temurin@17" = mkShell pkgs.temurin-bin-17;
        };
      });
}
