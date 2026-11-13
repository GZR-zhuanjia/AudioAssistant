#pragma once

// Small strict JSON reader for the private JNI request schema. No command strings
// or shell are involved; parsed values are passed directly to libav APIs.
#include <cmath>
#include <cstdlib>
#include <iomanip>
#include <map>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace audioassistant {
struct Json {
    enum Type { Null, Bool, Number, String, Array, Object } type{Null};
    bool boolean{};
    double number{};
    std::string string;
    std::vector<Json> array;
    std::map<std::string, Json> object;
    const Json &at(const std::string &key) const {
        auto it = object.find(key);
        if (type != Object || it == object.end()) throw std::runtime_error("Missing JSON field: " + key);
        return it->second;
    }
    std::string str() const { if (type != String) throw std::runtime_error("Expected JSON string"); return string; }
    double num() const { if (type != Number || !std::isfinite(number)) throw std::runtime_error("Expected finite JSON number"); return number; }
    bool flag() const { if (type != Bool) throw std::runtime_error("Expected JSON boolean"); return boolean; }
    static Json parse(const std::string &text) {
        size_t pos = 0;
        auto result = read(text, pos, 0);
        space(text, pos);
        if (pos != text.size()) throw std::runtime_error("Trailing JSON data");
        return result;
    }
    static std::string quote(const std::string &s) {
        std::ostringstream out; out << '"';
        for (unsigned char c : s) {
            switch(c) { case '"': out << "\\\""; break; case '\\': out << "\\\\"; break;
                case '\n': out << "\\n"; break; case '\r': out << "\\r"; break; case '\t': out << "\\t"; break;
                default: if (c < 32) out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << int(c); else out << c; }
        }
        return out.str() + '"';
    }
private:
    static void space(const std::string &s, size_t &p) { while (p < s.size() && (s[p]==' ' || s[p]=='\r' || s[p]=='\n' || s[p]=='\t')) ++p; }
    static void utf8(std::string &s, unsigned value) {
        if (value < 0x80) s += char(value);
        else if (value < 0x800) { s += char(0xc0 | (value >> 6)); s += char(0x80 | (value & 63)); }
        else if (value < 0x10000) { s += char(0xe0 | (value >> 12)); s += char(0x80 | ((value >> 6) & 63)); s += char(0x80 | (value & 63)); }
        else { s += char(0xf0 | (value >> 18)); s += char(0x80 | ((value >> 12) & 63)); s += char(0x80 | ((value >> 6) & 63)); s += char(0x80 | (value & 63)); }
    }
    static unsigned hex(const std::string &s, size_t &p) {
        unsigned n=0; for(int i=0;i<4;++i) { if(p>=s.size()) throw std::runtime_error("Invalid JSON unicode"); char c=s[p++]; n <<= 4;
            if(c>='0' && c<='9') n+=c-'0'; else if(c>='a' && c<='f') n+=c-'a'+10; else if(c>='A' && c<='F') n+=c-'A'+10; else throw std::runtime_error("Invalid JSON unicode"); }
        return n;
    }
    static std::string readString(const std::string &s, size_t &p) {
        if (p>=s.size() || s[p++]!='"') throw std::runtime_error("Expected JSON string");
        std::string out;
        while(p<s.size()) { unsigned char c=s[p++]; if(c=='"') return out; if(c<32) throw std::runtime_error("JSON control character");
            if(c!='\\') { out+=char(c); continue; } if(p>=s.size()) break; c=s[p++];
            switch(c) { case '"': case '\\': case '/': out+=char(c); break; case 'b': out+='\b'; break; case 'f': out+='\f'; break; case 'n': out+='\n'; break; case 'r': out+='\r'; break; case 't': out+='\t'; break;
                case 'u': { unsigned n=hex(s,p); if(n>=0xd800 && n<=0xdbff) { if(p+2>s.size() || s[p++]!='\\' || s[p++]!='u') throw std::runtime_error("Invalid surrogate"); unsigned low=hex(s,p); if(low<0xdc00 || low>0xdfff) throw std::runtime_error("Invalid surrogate"); n=0x10000+((n-0xd800)<<10)+(low-0xdc00); } else if(n>=0xdc00 && n<=0xdfff) throw std::runtime_error("Invalid surrogate"); utf8(out,n); break; }
                default: throw std::runtime_error("Invalid JSON escape"); }
        } throw std::runtime_error("Unterminated JSON string");
    }
    static Json read(const std::string &s, size_t &p, int depth) {
        if(depth>64) throw std::runtime_error("JSON nested too deeply");
        space(s,p);
        if(p>=s.size()) throw std::runtime_error("Unexpected JSON end");
        Json v;
        char c=s[p];
        if(c=='"') { v.type=String; v.string=readString(s,p); }
        else if(c=='{' || c=='[') { bool obj=c=='{'; v.type=obj?Object:Array; ++p; space(s,p); if(p<s.size() && s[p]==(obj?'}':']')) { ++p; return v; }
            while(true) { space(s,p); std::string key; if(obj) { key=readString(s,p); space(s,p); if(p>=s.size() || s[p++]!=':') throw std::runtime_error("Expected JSON colon"); }
                auto item=read(s,p,depth+1); if(obj) v.object.emplace(key,std::move(item)); else v.array.push_back(std::move(item)); space(s,p);
                if(p>=s.size()) throw std::runtime_error("Unexpected JSON end");
                char sep=s[p++];
                if(sep==(obj?'}':']')) break;
                if(sep!=',') throw std::runtime_error("Expected JSON comma"); }
        } else if(s.compare(p,4,"true")==0) { v.type=Bool; v.boolean=true; p+=4; }
        else if(s.compare(p,5,"false")==0) { v.type=Bool; p+=5; }
        else if(s.compare(p,4,"null")==0) { p+=4; }
        else { size_t start=p; if(s[p]=='-') ++p; if(p>=s.size()) throw std::runtime_error("Invalid JSON number");
            if(s[p]=='0') ++p; else { if(s[p]<'1' || s[p]>'9') throw std::runtime_error("Invalid JSON number"); while(p<s.size() && s[p]>='0' && s[p]<='9') ++p; }
            if(p<s.size() && s[p]=='.') { ++p; size_t digits=p; while(p<s.size() && s[p]>='0' && s[p]<='9') ++p; if(p==digits) throw std::runtime_error("Invalid JSON fraction"); }
            if(p<s.size() && (s[p]=='e' || s[p]=='E')) { ++p; if(p<s.size() && (s[p]=='+' || s[p]=='-')) ++p; size_t digits=p; while(p<s.size() && s[p]>='0' && s[p]<='9') ++p; if(p==digits) throw std::runtime_error("Invalid JSON exponent"); }
            v.type=Number; v.number=std::stod(s.substr(start,p-start)); }
        return v;
    }
};
} // namespace audioassistant
