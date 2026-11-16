#include "motonav/diagnostics/protocol.h"
#include <cassert>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>
using namespace motonav::diagnostics;
static std::vector<uint8_t> bytes(const std::string &text) {
    std::vector<uint8_t> result;
    for(size_t i=0;i<text.size();i+=2) result.push_back(uint8_t(std::stoul(text.substr(i,2),nullptr,16)));
    return result;
}
static std::vector<uint8_t> fragment(const std::vector<uint8_t> &b,size_t offset) {
    size_t size=std::min(FragmentPayload,b.size()-offset);
    std::vector<uint8_t> p{0xd1,b[8],uint8_t(offset),uint8_t(size)};
    p.insert(p.end(),b.begin()+offset,b.begin()+offset+size);return p;
}
int main(int argc,char **argv) {
    assert(argc==2);std::ifstream input(argv[1]);assert(input);std::string line;unsigned records=0;
    while(std::getline(input,line)) {
        if(line.empty())continue;
        auto b=bytes(line);Snapshot s{};assert(decode(b.data(),b.size(),s));++records;
        Assembler parser;
        for(size_t offset=0;offset<b.size();offset+=FragmentPayload) {
            auto p=fragment(b,offset);
            auto result=parser.feed(p.data(),p.size(),1000+offset,s);
            assert(result==(offset+FragmentPayload>=b.size()?Assembler::Result::Complete:Assembler::Result::Incomplete));
        }
        char json[1200];assert(format_json(s,4000,1,2,json,sizeof(json))>0);
        assert(std::string(json).find("\"receiver_dropped\":1")!=std::string::npos);
        assert(!format_json(s,4000,0,0,json,10));
        for(size_t i=0;i<b.size();++i) {
            auto corrupt=b;corrupt[i]^=1;assert(!decode(corrupt.data(),corrupt.size(),s));
        }
        auto first=fragment(b,0), second=fragment(b,16);
        parser.reset();assert(parser.feed(second.data(),second.size(),1000,s)==Assembler::Result::Invalid);
        assert(parser.feed(first.data(),first.size(),1000,s)==Assembler::Result::Incomplete);
        assert(parser.feed(second.data(),second.size(),4001,s)==Assembler::Result::Invalid);
        assert(parser.feed(first.data(),first.size(),5000,s)==Assembler::Result::Incomplete);
        parser.reset();assert(parser.feed(second.data(),second.size(),5001,s)==Assembler::Result::Invalid);
        auto bad=first;bad[3]=17;assert(parser.feed(bad.data(),bad.size(),6000,s)==Assembler::Result::Invalid);
        // A stopped or preempted sender can restart from offset zero without reconnecting.
        assert(parser.feed(first.data(),first.size(),7000,s)==Assembler::Result::Incomplete);
        for(size_t offset=0;offset<b.size();offset+=16){auto p=fragment(b,offset);auto r=parser.feed(p.data(),p.size(),8000+offset,s);if(offset==80)assert(r==Assembler::Result::Complete);}
    }
    assert(records==11);
    std::cout<<"PASS: DIAG1 Java-to-C++ decoding, CRC corruption, fragment ordering, timeout, reset and serial formatting ("<<records<<" fixtures)\n";
}
