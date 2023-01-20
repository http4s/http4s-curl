{
  inputs = {
    typelevel-nix.url = "github:typelevel/typelevel-nix";
    nixpkgs.follows = "typelevel-nix/nixpkgs";
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
      in {
        devShell = pkgs.devshell.mkShell {
          imports = [ typelevel-nix.typelevelShell ];
          name = "http4s-curl";
          typelevelShell = {
            jdk.package = pkgs.jdk8;
            native = {
              enable = true;
              libraries = [ curl ];
            };
          };
        };
      });
}
