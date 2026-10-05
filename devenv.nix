{
  pkgs,
  config,
  lib,
  ...
}: {
  packages = [];

  enterShell = ''
    ln -sfn "$JAVA_HOME" "$DEVENV_STATE/jdk"
  '';

  languages.java = {
    enable = true;
    jdk.package = pkgs.jdk25; # 顺带会提供一个跑在 JDK 25 上的 jdtls
  };

  env.JDK21 = pkgs.jdk21.home;
}
