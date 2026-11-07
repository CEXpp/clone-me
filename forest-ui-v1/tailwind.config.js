// 构建时仅扫描本站源码生成样式，字体使用本机系统字体，无需运行时 CDN 或远程字体。
export default {
  content: ["./index.html", "./src/**/*.{vue,ts}"],
  theme: {
    extend: {
      fontFamily: {
        sans: [
          "-apple-system",
          "BlinkMacSystemFont",
          "PingFang SC",
          "Helvetica Neue",
          "Microsoft YaHei",
          "sans-serif",
        ],
        mono: ["SFMono-Regular", "Consolas", "Liberation Mono", "monospace"],
      },
    },
  },
  plugins: [],
};
